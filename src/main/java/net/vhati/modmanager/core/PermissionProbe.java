package net.vhati.modmanager.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;


/**
 * Checks whether Slipstream can actually write where it's about to write,
 * before a patch or an extraction touches anything.
 *
 * File.canWrite() is not trusted here: it misses Windows ACL denies,
 * read-only mounts, and full disks. Each probe instead performs the very
 * operation the real write will use -- creating a temp file in a directory,
 * opening a dat read-write -- so a passing probe means the real operation
 * passes too. That also surfaces Windows file locks (a running game holds
 * its dats) at the same point where they'd otherwise strike mid-patch,
 * after backups were made and vanilla dats were partly restored.
 */
public class PermissionProbe {

	public enum Kind {
		/** The directory exists but no file could be created in it. */
		DIR_NOT_WRITABLE,
		/** The directory is absent and could not be created. */
		DIR_UNCREATABLE,
		/** The dat exists but could not be opened read-write. */
		DAT_NOT_WRITABLE,
		/** The dat is held by another process (Windows mandatory locks). */
		DAT_LOCKED
	}


	public static class Problem {
		public final Kind kind;
		public final File path;
		/** Raw cause, e.g. the failed open's message. May be null. */
		public final String detail;

		public Problem( Kind kind, File path, String detail ) {
			this.kind = kind;
			this.path = path;
			this.detail = detail;
		}
	}


	/**
	 * Checks the FTL install dir, the backup dir, and each dat that exists.
	 *
	 * The install dir is never created: it must already hold FTL's dats, and
	 * silently making an empty one would turn a wrong config path into a
	 * confusing later failure. The backup dir is Slipstream's own, so it is
	 * created when absent, mirroring AtomicFileOutput.
	 *
	 * @return an empty list when everything needed is writable
	 */
	public static List<Problem> probe( File datsDir, File backupDir, List<File> datFiles ) {
		List<Problem> problems = new ArrayList<Problem>();

		probeDir( datsDir, false, problems );
		if ( backupDir != null ) probeDir( backupDir, true, problems );

		for ( File datFile : datFiles ) {
			if ( !datFile.exists() ) continue;
			probeDat( datFile, problems );
		}

		return problems;
	}

	/**
	 * Checks a user-chosen extraction dir, creating it when absent.
	 *
	 * @return an empty list when the dir exists (or was created) and is writable
	 */
	public static List<Problem> probeExtractDir( File extractDir ) {
		List<Problem> problems = new ArrayList<Problem>();
		probeDir( extractDir, true, problems );
		return problems;
	}


	/**
	 * Formats problems for a human, with remediation advice for the current
	 * OS. The text goes into an exception message shown in the patch dialog
	 * and printed by the CLI, so it must be actionable without opening the
	 * log file.
	 */
	public static String describe( List<Problem> problems ) {
		String osName = System.getProperty( "os.name" );
		boolean isWindows = osName.startsWith( "Windows" );
		boolean isMac = osName.contains( "OS X" );

		StringBuilder buf = new StringBuilder();
		buf.append( "Slipstream cannot write where it needs to:\n\n" );

		for ( Problem problem : problems ) {
			buf.append( "- " ).append( problem.path.getAbsolutePath() );
			if ( problem.detail != null ) buf.append( ": " ).append( problem.detail );
			buf.append( "\n" );

			String advice = adviceFor( problem, isWindows, isMac );
			if ( advice != null ) buf.append( "  " ).append( advice ).append( "\n" );
		}
		return buf.toString();
	}


	private static void probeDir( File dir, boolean mayCreate, List<Problem> problems ) {
		if ( !dir.exists() ) {
			if ( !mayCreate || !dir.mkdirs() ) {
				problems.add( new Problem( Kind.DIR_UNCREATABLE, dir,
					"does not exist and could not be created" ) );
				return;
			}
		}
		else if ( !dir.isDirectory() ) {
			problems.add( new Problem( Kind.DIR_NOT_WRITABLE, dir, "not a directory" ) );
			return;
		}

		// The same create the real writes need: AtomicFileOutput stages a
		// temp file in the destination's own dir before renaming over it.
		File probeFile = null;
		try {
			probeFile = File.createTempFile( ".slipstream-probe-", ".tmp", dir );
		}
		catch ( IOException e ) {
			problems.add( new Problem( Kind.DIR_NOT_WRITABLE, dir, e.getMessage() ) );
		}
		finally {
			if ( probeFile != null ) probeFile.delete();
		}
	}

	private static void probeDat( File datFile, List<Problem> problems ) {
		// The same open FTLPack/PkgPack will do, so a lock or an ACL deny is
		// caught exactly when the real patch would have hit it.
		RandomAccessFile raf = null;
		try {
			raf = new RandomAccessFile( datFile, "rw" );
		}
		catch ( IOException e ) {
			problems.add( new Problem( classify( e ), datFile, e.getMessage() ) );
		}
		finally {
			if ( raf != null ) {
				try {raf.close();}
				catch ( IOException e ) {}
			}
		}
	}

	private static Kind classify( IOException e ) {
		String message = e.getMessage();
		if ( message != null ) {
			String lower = message.toLowerCase();
			if ( lower.contains( "used by another process" ) || lower.contains( "being used" ) || lower.contains( "locked" ) ) {
				return Kind.DAT_LOCKED;
			}
		}
		return Kind.DAT_NOT_WRITABLE;
	}

	private static String adviceFor( Problem problem, boolean isWindows, boolean isMac ) {
		if ( problem.kind == Kind.DAT_LOCKED ) {
			return "Close FTL (and anything else holding the file open), then patch again.";
		}

		if ( isWindows ) {
			if ( problem.kind == Kind.DIR_UNCREATABLE ) {
				return "Slipstream could not create its own folder here. Move Slipstream somewhere you can write (your Documents folder, say) and run it again.";
			}
			return "Windows denied the write. If FTL is installed under \"Program Files\", run Slipstream through modman_admin.exe (or right-click \u2192 Run as administrator). Otherwise clear the file's read-only attribute and check the folder's Security tab for Deny entries.";
		}

		// POSIX: name the owner so "just chmod it" guesses aren't needed, and
		// give the command for the right target. A dat's parent dir is the
		// target because restoring vanilla also needs to create/rename files
		// in that dir, not merely write inside the dat.
		File target;
		switch ( problem.kind ) {
			case DAT_NOT_WRITABLE:  target = problem.path.getParentFile(); break;
			case DIR_UNCREATABLE:   target = problem.path.getParentFile(); break;
			default:                target = problem.path;
		}
		if ( target == null ) target = problem.path;

		StringBuilder buf = new StringBuilder();

		String owner = ownerName( problem.path );
		String user = System.getProperty( "user.name" );
		if ( owner != null && user != null && owner.equals( user ) ) {
			buf.append( "You own this but lack write permission. Run:\n    chmod -R u+w \"" ).append( target.getAbsolutePath() ).append( "\"" );
		}
		else {
			if ( owner != null ) buf.append( "Owned by \"").append( owner ).append( "\", you are \"").append( user ).append( "\". Run:\n    " );
			else buf.append( "Run:\n    " );
			buf.append( "sudo chown -R \"$(whoami)\" \"" ).append( target.getAbsolutePath() ).append( "\"" );
		}

		if ( isMac && underTccProtectedDir( problem.path ) ) {
			buf.append( "\n    macOS may also be blocking this folder: grant Full Disk Access to the app launching Slipstream (System Settings \u2192 Privacy & Security)." );
		}
		return buf.toString();
	}

	private static String ownerName( File f ) {
		// A dir that failed to be created has no owner of its own; the
		// existing ancestor that refused the mkdir does.
		File probe = f;
		while ( probe != null && !probe.exists() ) probe = probe.getParentFile();
		if ( probe == null ) return null;

		try {
			return Files.getOwner( probe.toPath() ).getName();
		}
		catch ( Exception e ) {
			return null;  // FAT, some network shares: no owner concept.
		}
	}

	/** macOS TCC blocks these unless the launching app has Full Disk Access. */
	private static boolean underTccProtectedDir( File f ) {
		String home = System.getProperty( "user.home" );
		if ( home == null ) return false;

		String path;
		try {path = f.getCanonicalPath();}
		catch ( IOException e ) {path = f.getAbsolutePath();}

		return path.startsWith( new File( home, "Documents" ).getAbsolutePath() ) ||
		       path.startsWith( new File( home, "Desktop" ).getAbsolutePath() );
	}
}
