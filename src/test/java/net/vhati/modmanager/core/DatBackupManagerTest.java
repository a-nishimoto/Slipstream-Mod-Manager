package net.vhati.modmanager.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Guards the app's core promise: a user can always get back to vanilla FTL.
 *
 * The .dat contents here are plain text. DatBackupManager only copies and
 * hashes whole files, so the archive format is irrelevant to what is tested.
 */
public class DatBackupManagerTest {

	private File datsDir;
	private File backupDir;

	private void layout( File tmpDir ) throws Exception {
		datsDir = new File( tmpDir, "dats" );
		backupDir = new File( tmpDir, "backup" );
		assertTrue( datsDir.mkdirs() );
		assertTrue( backupDir.mkdirs() );
	}

	private void writeDat( String name, String content ) throws Exception {
		FileWriter w = new FileWriter( new File( datsDir, name ) );
		try {
			w.write( content );
		}
		finally {
			w.close();
		}
	}

	private String readDat( String name ) throws Exception {
		return new String( Files.readAllBytes( new File( datsDir, name ).toPath() ), StandardCharsets.UTF_8 );
	}

	private File bak( String datName ) {
		return new File( backupDir, datName +".bak" );
	}

	private DatBackupManager manager() throws IOException {
		return new DatBackupManager( datsDir, backupDir );
	}


	@Test
	public void firstRunBacksUpAndNeedsNoRestore( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		writeDat( "resource.dat", "VANILLA-RESOURCE" );

		boolean restoreNeeded = manager().prepareBackups( null, null );

		assertFalse( restoreNeeded, "backups were just taken from the dats, so no restore is due" );
		assertTrue( bak( "data.dat" ).exists() );
		assertTrue( bak( "resource.dat" ).exists() );
		assertTrue( new File( backupDir, BackupManifest.FILENAME ).exists(), "manifest not written" );
	}


	@Test
	public void secondRunVerifiesAndRestores( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		writeDat( "resource.dat", "VANILLA-RESOURCE" );
		manager().prepareBackups( null, null );

		// A patch run modifies the dats.
		writeDat( "data.dat", "MODDED" );

		DatBackupManager m = manager();
		assertTrue( m.prepareBackups( null, null ), "a restore is due" );
		m.restoreVanilla( null, null );

		assertEquals( "VANILLA-DATA", readDat( "data.dat" ) );
		assertEquals( "VANILLA-RESOURCE", readDat( "resource.dat" ) );
	}


	/**
	 * The defect this class exists to prevent.
	 *
	 * Old behavior: a missing .bak was silently recreated from the CURRENT dat.
	 * If that dat was already modded, the modded bytes became the permanent
	 * "vanilla" reference and every later revert restored them. Unrecoverable
	 * short of reinstalling FTL.
	 */
	@Test
	public void aPartialBackupSetIsRefusedRatherThanRebuiltFromModdedDats( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		writeDat( "resource.dat", "VANILLA-RESOURCE" );
		manager().prepareBackups( null, null );

		// Mods get installed, then one backup disappears -- antivirus, cloud
		// sync, a partially failed "Delete Backups", a manual cleanup.
		writeDat( "data.dat", "MODDED" );
		assertTrue( bak( "data.dat" ).delete() );

		IOException e = assertThrows( IOException.class, () -> manager().prepareBackups( null, null ) );
		assertTrue( e.getMessage().contains( "data.dat.bak" ), "message should name the missing backup" );

		// Crucially, it did not resurrect the backup from the modded dat.
		assertFalse( bak( "data.dat" ).exists(), "a modded dat was backed up as vanilla" );
		assertEquals( "VANILLA-RESOURCE",
			new String( Files.readAllBytes( bak( "resource.dat" ).toPath() ), StandardCharsets.UTF_8 ),
			"the surviving vanilla backup must be left untouched" );
	}


	@Test
	public void anAlteredBackupIsRefused( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		manager().prepareBackups( null, null );

		FileWriter w = new FileWriter( bak( "data.dat" ) );
		try {
			w.write( "TAMPERED" );
		}
		finally {
			w.close();
		}

		IOException e = assertThrows( IOException.class, () -> manager().prepareBackups( null, null ) );
		assertTrue( e.getMessage().contains( "has changed" ), "message should say the backup changed" );
	}


	/**
	 * Installs predating the manifest must keep working. A complete set was
	 * always created all-or-nothing, so it is adopted rather than rejected.
	 */
	@Test
	public void legacyBackupsWithoutAManifestAreAdopted( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		manager().prepareBackups( null, null );
		assertTrue( new File( backupDir, BackupManifest.FILENAME ).delete() );

		DatBackupManager m = manager();
		assertTrue( m.prepareBackups( null, null ), "legacy backups should still drive a restore" );
		assertTrue( new File( backupDir, BackupManifest.FILENAME ).exists(), "hashes should now be recorded" );
	}


	/**
	 * Backups vanishing while the manifest remains means something outside
	 * Slipstream removed them; the dats cannot be assumed vanilla.
	 */
	@Test
	public void backupsMissingWhileManifestRemainsIsRefused( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		manager().prepareBackups( null, null );

		writeDat( "data.dat", "MODDED" );
		assertTrue( bak( "data.dat" ).delete() );

		IOException e = assertThrows( IOException.class, () -> manager().prepareBackups( null, null ) );
		assertTrue( e.getMessage().contains( BackupManifest.FILENAME ) );
		assertFalse( bak( "data.dat" ).exists(), "a modded dat was backed up as vanilla" );
	}


	/**
	 * Cancelling partway must not leave the half-set that the partial-set check
	 * would later refuse to work with.
	 */
	@Test
	public void cancellingDuringBackupRollsBack( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		writeDat( "resource.dat", "VANILLA-RESOURCE" );

		DatBackupManager.CancelCheck stopAfterFirst = new DatBackupManager.CancelCheck() {
			private int calls = 0;
			@Override
			public boolean shouldContinue() {
				return ++calls < 1;
			}
		};

		assertThrows( DatBackupManager.BackupCancelledException.class,
			() -> manager().prepareBackups( null, stopAfterFirst ) );

		assertFalse( bak( "data.dat" ).exists(), "partial backup left behind" );
		assertFalse( bak( "resource.dat" ).exists(), "partial backup left behind" );
		assertFalse( new File( backupDir, BackupManifest.FILENAME ).exists(), "manifest written for an incomplete set" );

		// And the next run starts cleanly rather than being stuck.
		assertFalse( manager().prepareBackups( null, null ) );
		assertTrue( bak( "data.dat" ).exists() );
		assertTrue( bak( "resource.dat" ).exists() );
	}


	@Test
	public void deleteRemovesBackupsAndManifestTogether( @TempDir File tmpDir ) throws Exception {
		layout( tmpDir );
		writeDat( "data.dat", "VANILLA-DATA" );
		manager().prepareBackups( null, null );

		assertTrue( manager().deleteBackups().isEmpty(), "deletion reported failures" );

		assertFalse( bak( "data.dat" ).exists() );
		assertFalse( new File( backupDir, BackupManifest.FILENAME ).exists(),
			"a stale manifest would make the next run refuse to start" );

		// Back to first-run semantics.
		assertFalse( manager().prepareBackups( null, null ) );
	}
}
