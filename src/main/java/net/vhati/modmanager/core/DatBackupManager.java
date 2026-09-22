package net.vhati.modmanager.core;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import net.vhati.ftldat.PackUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Owns the promise that a user can always get back to vanilla FTL.
 *
 * Slipstream does not journal per-mod undo. Reverting means copying pristine
 * .dat files back over the game's, so the backups ARE the guarantee, and a
 * backup taken of an already-patched dat destroys it permanently.
 *
 * The rule that makes this safe is simple: a backup may only ever be created
 * from dats believed vanilla, which is only true when no backups exist at all.
 * Any other shape -- some backups present but not others, or backups whose
 * hashes do not match the manifest -- means something happened outside
 * Slipstream's control, and the safe response is to stop and tell the user
 * rather than guess. The old code guessed: it recreated whichever .bak was
 * missing from the current dat, which silently promoted modded data to vanilla.
 *
 * Creating a backup set is all-or-nothing. Backups written first are rolled back
 * if a later one fails or the user cancels, so a half-set is never left behind,
 * and the manifest is saved only once every backup is on disk.
 */
public class DatBackupManager {

	private static final Logger log = LoggerFactory.getLogger( DatBackupManager.class );

	private static final String[] DAT_NAMES = new String[] {"ftl.dat", "data.dat", "resource.dat"};


	/** Receives status text and progress while backups are copied. */
	public interface ProgressListener {
		public void backupStatus( String message );
		public void backupProgress( int done, int total );
	}

	/** Lets a caller abort a long copy. */
	public interface CancelCheck {
		public boolean shouldContinue();
	}

	public static class BackedUpDat {
		public File datFile = null;
		public File bakFile = null;
	}


	private static final ProgressListener NULL_PROGRESS = new ProgressListener() {
		@Override
		public void backupStatus( String message ) {}
		@Override
		public void backupProgress( int done, int total ) {}
	};

	private static final CancelCheck NEVER_CANCEL = new CancelCheck() {
		@Override
		public boolean shouldContinue() {return true;}
	};


	private final File backupDir;
	private final List<BackedUpDat> dats = new ArrayList<BackedUpDat>( 3 );
	private final BackupManifest manifest;


	public DatBackupManager( File datsDir, File backupDir ) throws IOException {
		this.backupDir = backupDir;

		for ( String datName : DAT_NAMES ) {
			File datFile = new File( datsDir, datName );
			if ( !datFile.exists() ) continue;

			BackedUpDat bud = new BackedUpDat();
			bud.datFile = datFile;
			bud.bakFile = new File( backupDir, datName +".bak" );
			dats.add( bud );
		}

		this.manifest = BackupManifest.load( backupDir );
	}


	public List<BackedUpDat> getDats() {
		return dats;
	}

	private int countBackups() {
		int result = 0;
		for ( BackedUpDat bud : dats ) {
			if ( bud.bakFile.exists() ) result++;
		}
		return result;
	}

	public boolean hasAnyBackups() {
		return countBackups() > 0;
	}

	public boolean hasCompleteBackups() {
		return !dats.isEmpty() && countBackups() == dats.size();
	}


	private static String md5( File f ) throws IOException {
		try {
			return PackUtilities.calcFileMD5( f );
		}
		catch ( NoSuchAlgorithmException e ) {
			throw new IOException( "MD5 is unavailable on this JVM, so backups cannot be verified", e );
		}
	}


	/**
	 * Ensures a trustworthy full backup set exists.
	 *
	 * If none exists, the current dats are treated as vanilla and backed up. If
	 * a complete set exists, it is verified against the manifest. Anything else
	 * throws.
	 *
	 * @return true if the dats should now be restored from those backups, false
	 *         if the backups were just created (so the dats are already vanilla)
	 * @throws IOException if the backup set is partial, unverifiable, or absent
	 *         when the manifest says it should not be
	 */
	public boolean prepareBackups( ProgressListener progress, CancelCheck cancel ) throws IOException {
		if ( progress == null ) progress = NULL_PROGRESS;
		if ( cancel == null ) cancel = NEVER_CANCEL;

		if ( dats.isEmpty() ) throw new IOException( "No FTL .dat files were found to back up." );

		int backupCount = countBackups();

		if ( backupCount == 0 ) {
			if ( !manifest.isEmpty() ) {
				throw new IOException( String.format(
					"The vanilla backups are gone but \"%s\" still lists them, so FTL's current"
					+ " .dat files cannot be assumed vanilla. Slipstream will not back up files it"
					+ " cannot vouch for, because that would permanently record modded data as"
					+ " vanilla.\n\nReinstall FTL (or use Steam's \"Verify integrity of game"
					+ " files\"), then delete \"%s\" from the backup folder.",
					BackupManifest.FILENAME, BackupManifest.FILENAME ) );
			}

			createBackups( progress, cancel );
			return false;  // Just copied FROM the dats, so they are already vanilla.
		}

		if ( backupCount < dats.size() ) {
			StringBuilder missingBuf = new StringBuilder();
			for ( BackedUpDat bud : dats ) {
				if ( !bud.bakFile.exists() ) {
					if ( missingBuf.length() > 0 ) missingBuf.append( ", " );
					missingBuf.append( bud.bakFile.getName() );
				}
			}
			throw new IOException( String.format(
				"Some vanilla backups are missing (%s) while others remain. Slipstream cannot"
				+ " recreate the missing ones, because FTL's current .dat files may already be"
				+ " modded -- backing them up now would record modded data as vanilla"
				+ " permanently.\n\nReinstall FTL (or use Steam's \"Verify integrity of game"
				+ " files\"), then use \"Help > Delete Backups\" to start over.",
				missingBuf ) );
		}

		// A complete set. Verify it if we can.
		if ( manifest.isEmpty() ) {
			// Backups predating the manifest. They cannot be verified, but they
			// were made by the same all-or-nothing rule, so adopt them rather
			// than refusing to run for every existing install.
			log.warn( "Backups exist without a manifest; adopting their current hashes as the vanilla baseline." );
			progress.backupStatus( "Recording backup checksums..." );

			for ( BackedUpDat bud : dats ) {
				manifest.setHash( bud.bakFile.getName(), md5( bud.bakFile ) );
			}
			manifest.save();
		}
		else {
			progress.backupStatus( "Verifying vanilla backups..." );

			for ( BackedUpDat bud : dats ) {
				String expected = manifest.getHash( bud.bakFile.getName() );
				if ( expected == null ) {
					throw new IOException( String.format(
						"The backup \"%s\" is not listed in \"%s\", so it cannot be verified as"
						+ " vanilla. Reinstall FTL, then use \"Help > Delete Backups\" to start over.",
						bud.bakFile.getName(), BackupManifest.FILENAME ) );
				}

				String actual = md5( bud.bakFile );
				if ( !expected.equals( actual ) ) {
					throw new IOException( String.format(
						"The backup \"%s\" has changed since it was created (expected MD5 %s, found"
						+ " %s). It is no longer a trustworthy copy of vanilla FTL, so Slipstream"
						+ " will not restore from it.\n\nReinstall FTL (or use Steam's \"Verify"
						+ " integrity of game files\"), then use \"Help > Delete Backups\" to start"
						+ " over.",
						bud.bakFile.getName(), expected, actual ) );
				}
			}
		}

		progress.backupStatus( null );
		return true;
	}


	/**
	 * Copies every dat to its backup, rolling back if anything fails.
	 */
	private void createBackups( ProgressListener progress, CancelCheck cancel ) throws IOException {
		List<BackedUpDat> created = new ArrayList<BackedUpDat>( dats.size() );

		try {
			for ( BackedUpDat bud : dats ) {
				log.info( String.format( "Backing up \"%s\".", bud.datFile.getName() ) );
				progress.backupStatus( String.format( "Backing up \"%s\".", bud.datFile.getName() ) );

				PackUtilities.copyFile( bud.datFile, bud.bakFile );
				created.add( bud );
				progress.backupProgress( created.size(), dats.size() );

				if ( !cancel.shouldContinue() ) {
					throw new BackupCancelledException();
				}
			}

			// Only now is the set complete, so only now is the manifest valid.
			for ( BackedUpDat bud : dats ) {
				manifest.setHash( bud.bakFile.getName(), md5( bud.bakFile ) );
			}
			manifest.save();
		}
		catch ( IOException e ) {
			rollback( created );
			throw e;
		}
		catch ( RuntimeException e ) {
			rollback( created );
			throw e;
		}

		progress.backupStatus( null );
	}

	private void rollback( List<BackedUpDat> created ) {
		if ( created.isEmpty() ) return;

		log.warn( "Backup did not complete; discarding the partial set so it is not mistaken for vanilla." );
		for ( BackedUpDat bud : created ) {
			if ( bud.bakFile.exists() && !bud.bakFile.delete() ) {
				log.error( String.format( "Unable to delete incomplete backup \"%s\"; delete it manually"
					+ " before patching again.", bud.bakFile.getAbsolutePath() ) );
			}
		}
		manifest.delete();
	}


	/**
	 * Copies the verified backups back over FTL's dats.
	 *
	 * Call prepareBackups() first; this does not re-verify.
	 */
	public void restoreVanilla( ProgressListener progress, CancelCheck cancel ) throws IOException {
		if ( progress == null ) progress = NULL_PROGRESS;
		if ( cancel == null ) cancel = NEVER_CANCEL;

		int done = 0;
		for ( BackedUpDat bud : dats ) {
			log.info( String.format( "Restoring vanilla \"%s\"...", bud.datFile.getName() ) );
			progress.backupStatus( String.format( "Restoring vanilla \"%s\"...", bud.datFile.getName() ) );

			PackUtilities.copyFile( bud.bakFile, bud.datFile );
			progress.backupProgress( ++done, dats.size() );

			if ( !cancel.shouldContinue() ) throw new BackupCancelledException();
		}
		progress.backupStatus( null );
	}


	/**
	 * Removes the backups and the manifest together.
	 *
	 * These must go together. Leaving the manifest behind would make the next
	 * run refuse to start (it would see recorded backups that no longer exist),
	 * and leaving backups without a manifest would silently drop them back to
	 * unverifiable legacy status.
	 *
	 * This deliberately does NOT restore vanilla first: the dats can be
	 * hundreds of megabytes and callers run on the Swing EDT. It is the
	 * caller's job to tell the user to revert before deleting -- reverting is
	 * just a patch with no mods selected.
	 *
	 * @return names of files that could not be deleted
	 */
	public List<String> deleteBackups() {
		List<String> failures = new ArrayList<String>( 4 );

		for ( String datName : DAT_NAMES ) {
			File bakFile = new File( backupDir, datName +".bak" );
			if ( bakFile.exists() && !bakFile.delete() ) {
				log.error( "Unable to delete backup: "+ bakFile.getName() );
				failures.add( bakFile.getName() );
			}
		}

		if ( !manifest.delete() ) failures.add( BackupManifest.FILENAME );

		return failures;
	}


	/** Thrown when the user aborts a backup or restore partway. */
	public static class BackupCancelledException extends IOException {
		public BackupCancelledException() {
			super( "Cancelled." );
		}
	}
}
