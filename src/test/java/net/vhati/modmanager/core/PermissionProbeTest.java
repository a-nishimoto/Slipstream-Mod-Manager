package net.vhati.modmanager.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The probe must fail exactly when the real patch would fail: read-only
 * dats and unwritable dirs flagged, healthy layouts clean, and remediation
 * text matching the OS the user is actually on.
 */
public class PermissionProbeTest {

	private static boolean isWindows() {
		return System.getProperty( "os.name" ).startsWith( "Windows" );
	}

	private static File touch( File dir, String name ) throws IOException {
		File f = new File( dir, name );
		assertTrue( f.createNewFile() );
		return f;
	}

	private static void setOwnerOnly( File dir, boolean writable ) throws IOException {
		EnumSet<PosixFilePermission> perms = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE );
		if ( writable ) perms.add( PosixFilePermission.OWNER_WRITE );
		Files.setPosixFilePermissions( dir.toPath(), perms );
	}


	@Test
	public void allWritableYieldsNoProblems( @TempDir File tmpDir ) throws Exception {
		File datsDir = new File( tmpDir, "dats" );
		File backupDir = new File( tmpDir, "backup" );
		assertTrue( datsDir.mkdirs() );
		assertTrue( backupDir.mkdirs() );
		File dat = touch( datsDir, "ftl.dat" );

		List<PermissionProbe.Problem> problems =
			PermissionProbe.probe( datsDir, backupDir, Arrays.asList( dat ) );

		assertEquals( 0, problems.size() );
	}

	@Test
	public void readOnlyDatIsFlagged( @TempDir File tmpDir ) throws Exception {
		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );
		File dat = touch( datsDir, "ftl.dat" );
		assertTrue( dat.setReadOnly() );

		// Root ignores the permission bits, so the probe would legitimately
		// pass and the test's premise is void.
		Assumptions.assumeTrue( !dat.canWrite(), "read-only flag not honored (running as root?)" );

		try {
			List<PermissionProbe.Problem> problems =
				PermissionProbe.probe( datsDir, null, Arrays.asList( dat ) );

			assertEquals( 1, problems.size() );
			assertEquals( PermissionProbe.Kind.DAT_NOT_WRITABLE, problems.get( 0 ).kind );
			assertEquals( dat, problems.get( 0 ).path );
		}
		finally {
			dat.setWritable( true );
		}
	}

	@Test
	public void unwritableDatsDirIsFlagged( @TempDir File tmpDir ) throws Exception {
		Assumptions.assumeFalse( isWindows(), "relies on POSIX permission bits" );

		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );

		try {
			setOwnerOnly( datsDir, false );
			Assumptions.assumeTrue( !datsDir.canWrite(), "permission bits not honored (running as root?)" );

			List<PermissionProbe.Problem> problems =
				PermissionProbe.probe( datsDir, null, Arrays.<File>asList() );

			assertEquals( 1, problems.size() );
			assertEquals( PermissionProbe.Kind.DIR_NOT_WRITABLE, problems.get( 0 ).kind );
			assertEquals( datsDir, problems.get( 0 ).path );
		}
		finally {
			// @TempDir cleanup needs the dir deletable again.
			setOwnerOnly( datsDir, true );
		}
	}

	@Test
	public void uncreatableBackupDirIsFlagged( @TempDir File tmpDir ) throws Exception {
		Assumptions.assumeFalse( isWindows(), "relies on POSIX permission bits" );

		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );
		File lockedParent = new File( tmpDir, "locked" );
		assertTrue( lockedParent.mkdirs() );
		File backupDir = new File( lockedParent, "backup" );

		try {
			setOwnerOnly( lockedParent, false );
			Assumptions.assumeTrue( !lockedParent.canWrite(), "permission bits not honored (running as root?)" );

			List<PermissionProbe.Problem> problems =
				PermissionProbe.probe( datsDir, backupDir, Arrays.<File>asList() );

			assertEquals( 1, problems.size() );
			assertEquals( PermissionProbe.Kind.DIR_UNCREATABLE, problems.get( 0 ).kind );
			assertEquals( backupDir, problems.get( 0 ).path );
			assertFalse( backupDir.exists() );
		}
		finally {
			setOwnerOnly( lockedParent, true );
		}
	}

	@Test
	public void missingBackupDirIsCreated( @TempDir File tmpDir ) throws Exception {
		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );
		File backupDir = new File( tmpDir, "backup" );

		List<PermissionProbe.Problem> problems =
			PermissionProbe.probe( datsDir, backupDir, Arrays.<File>asList() );

		assertEquals( 0, problems.size() );
		assertTrue( backupDir.isDirectory() );
	}

	@Test
	public void missingDatsDirIsNotCreated( @TempDir File tmpDir ) throws Exception {
		// A wrong config path must not silently become an empty dir.
		File datsDir = new File( tmpDir, "nope/dats" );
		File backupDir = new File( tmpDir, "backup" );

		List<PermissionProbe.Problem> problems =
			PermissionProbe.probe( datsDir, backupDir, Arrays.<File>asList() );

		assertFalse( datsDir.exists() );
		assertEquals( 1, problems.size() );
		assertEquals( PermissionProbe.Kind.DIR_UNCREATABLE, problems.get( 0 ).kind );
	}

	@Test
	public void absentDatsAreNotFlagged( @TempDir File tmpDir ) throws Exception {
		// patch() tolerates missing dats (older FTL versions use different
		// sets); only existing ones are probed.
		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );

		List<PermissionProbe.Problem> problems =
			PermissionProbe.probe( datsDir, null, Arrays.asList( new File( datsDir, "data.dat" ) ) );

		assertEquals( 0, problems.size() );
	}

	@Test
	public void extractDirIsCreatedAndProbed( @TempDir File tmpDir ) throws Exception {
		File extractDir = new File( tmpDir, "some/where" );

		List<PermissionProbe.Problem> problems = PermissionProbe.probeExtractDir( extractDir );

		assertEquals( 0, problems.size() );
		assertTrue( extractDir.isDirectory() );
	}

	@Test
	public void probeLeavesNoTempFilesBehind( @TempDir File tmpDir ) throws Exception {
		File datsDir = new File( tmpDir, "dats" );
		assertTrue( datsDir.mkdirs() );
		File backupDir = new File( tmpDir, "backup" );

		PermissionProbe.probe( datsDir, backupDir, Arrays.<File>asList() );

		assertEquals( 0, countProbeFiles( datsDir ) );
		assertEquals( 0, countProbeFiles( backupDir ) );
	}

	private static int countProbeFiles( File dir ) {
		File[] leftovers = dir.listFiles( (f, name) -> name.startsWith( ".slipstream-probe-" ) );
		return leftovers == null ? -1 : leftovers.length;
	}


	@Test
	public void describeGivesWindowsAdminAdvice( @TempDir File tmpDir ) throws Exception {
		PermissionProbe.Problem problem = new PermissionProbe.Problem(
			PermissionProbe.Kind.DAT_NOT_WRITABLE, new File( tmpDir, "ftl.dat" ), "Access is denied" );

		String osName = System.getProperty( "os.name" );
		try {
			System.setProperty( "os.name", "Windows 10" );
			String text = PermissionProbe.describe( Arrays.asList( problem ) );

			assertTrue( text.contains( "modman_admin.exe" ), text );
			assertFalse( text.contains( "chown" ), text );
		}
		finally {
			System.setProperty( "os.name", osName );
		}
	}

	@Test
	public void describeTellsUserToCloseTheGame( @TempDir File tmpDir ) throws Exception {
		PermissionProbe.Problem problem = new PermissionProbe.Problem(
			PermissionProbe.Kind.DAT_LOCKED, new File( tmpDir, "ftl.dat" ),
			"The process cannot access the file because it is being used by another process" );

		String text = PermissionProbe.describe( Arrays.asList( problem ) );

		assertTrue( text.contains( "Close FTL" ), text );
	}

	@Test
	public void describeGivesPosixPermissionAdvice( @TempDir File tmpDir ) throws Exception {
		File dat = touch( tmpDir, "ftl.dat" );
		PermissionProbe.Problem problem = new PermissionProbe.Problem(
			PermissionProbe.Kind.DAT_NOT_WRITABLE, dat, "Permission denied" );

		String osName = System.getProperty( "os.name" );
		try {
			System.setProperty( "os.name", "Linux" );
			String text = PermissionProbe.describe( Arrays.asList( problem ) );

			// The advice targets the dat's parent dir, since restoring
			// vanilla also needs to create/rename files there.
			assertTrue( text.contains( tmpDir.getAbsolutePath() ), text );

			// Which command depends on who owns the temp file: the test user
			// (chmod form) or not (chown form). The chown form needs a
			// foreign owner to exercise, which tests can't portably create.
			String owner = Files.getOwner( dat.toPath() ).getName();
			if ( owner.equals( System.getProperty( "user.name" ) ) ) {
				assertTrue( text.contains( "chmod -R u+w" ), text );
			}
			else {
				assertTrue( text.contains( "sudo chown" ), text );
			}
		}
		finally {
			System.setProperty( "os.name", osName );
		}
	}
}
