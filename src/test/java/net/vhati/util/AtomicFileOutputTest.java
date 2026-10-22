package net.vhati.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class AtomicFileOutputTest {

	private static String read( File f ) throws Exception {
		return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
	}

	private static void seed( File f, String content ) throws Exception {
		FileWriter w = new FileWriter( f );
		try {
			w.write( content );
		}
		finally {
			w.close();
		}
	}

	/**
	 * Denies new writes in dir -- Files.move into it then throws
	 * AccessDeniedException -- and leaves it that way for the caller to
	 * restore. Returns false, with writes left allowed, where the denial is
	 * not real (root ignores mode bits), so callers skip instead of passing
	 * vacuously.
	 */
	private static boolean lockWrites( File dir ) throws IOException {
		if ( !dir.setWritable( false ) ) return false;
		File probe = new File( dir, ".deny-probe" );
		try ( FileOutputStream fos = new FileOutputStream( probe ) ) {
			dir.setWritable( true );
			return false;
		}
		catch ( FileNotFoundException e ) {
			probe.delete();
			return true;
		}
	}


	@Test
	public void commitReplacesTheDestination( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "config.txt" );
		seed( dst, "OLD" );

		AtomicFileOutput out = new AtomicFileOutput( dst );
		try {
			out.getOutputStream().write( "NEW".getBytes( StandardCharsets.UTF_8 ) );
			out.commit();
		}
		finally {
			out.close();
		}

		assertEquals( "NEW", read( dst ) );
		assertEquals( 1, tmpDir.listFiles().length, "staged file was left behind" );
	}


	/**
	 * The point of the class: an abandoned write must not damage what is
	 * already on disk. This is the vanilla-backup and downloaded-catalog case.
	 */
	@Test
	public void abandonedWriteLeavesTheOriginalIntact( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "vanilla.dat.bak" );
		seed( dst, "PRISTINE" );

		AtomicFileOutput out = new AtomicFileOutput( dst );
		try {
			out.getOutputStream().write( "HALF-WRITTEN GARBAGE".getBytes( StandardCharsets.UTF_8 ) );
			// Simulates a crash, cancel, or exception: no commit().
		}
		finally {
			out.close();
		}

		assertEquals( "PRISTINE", read( dst ), "destination was clobbered by an uncommitted write" );
		assertEquals( 1, tmpDir.listFiles().length, "staged file was left behind" );
	}


	@Test
	public void createsTheDestinationWhenAbsent( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "sub/dir/new.txt" );

		AtomicFileOutput out = new AtomicFileOutput( dst );
		try {
			Writer w = out.getWriter( "UTF-8" );
			w.write( "hello" );
			out.commit();  // commit() flushes the writer for us.
		}
		finally {
			out.close();
		}

		assertTrue( dst.exists() );
		assertEquals( "hello", read( dst ) );
	}


	@Test
	public void closeIsIdempotentAndCommitIsOnce( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "once.txt" );

		AtomicFileOutput out = new AtomicFileOutput( dst );
		out.getOutputStream().write( "x".getBytes( StandardCharsets.UTF_8 ) );
		out.commit();
		out.close();
		out.close();

		assertThrows( IllegalStateException.class, () -> out.commit() );
	}


	/**
	 * A Windows AV scan of the just-written staged file, or the game holding
	 * the destination, makes Files.move throw AccessDeniedException; that used
	 * to fail the entire patch after every byte was already written. commit()
	 * now waits such a lock out.
	 *
	 * The denial is simulated POSIX-style with a read-only parent dir; the
	 * scenario cannot be built where mode bits are not honored (root), so skip.
	 */
	@Test
	public void aTransientMoveDenialIsRetriedUntilItLifts( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "config.dat" );
		seed( dst, "OLD" );

		AtomicFileOutput out = new AtomicFileOutput( dst );  // stages while still writable
		Thread releaser = new Thread( () -> {
			try { Thread.sleep( 600 ); }
			catch ( InterruptedException ie ) {}
			tmpDir.setWritable( true );
		} );
		try {
			out.getOutputStream().write( "NEW".getBytes( StandardCharsets.UTF_8 ) );
			Assumptions.assumeTrue( lockWrites( tmpDir ), "cannot deny writes in "+ tmpDir );
			releaser.start();
			out.commit();
			assertEquals( "NEW", read( dst ) );
		}
		finally {
			try { releaser.join(); }
			catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
			tmpDir.setWritable( true );
			out.close();
		}
	}


	/**
	 * A denial that never lifts must still spare the staged file: the payload
	 * is complete work, and on Windows it usually cannot even be deleted while
	 * locked. A delayed cleanup, or the user, can move it later.
	 */
	@Test
	public void aPermanentMoveDenialKeepsTheStagedFile( @TempDir File tmpDir ) throws Exception {
		File dst = new File( tmpDir, "config.dat" );
		seed( dst, "OLD" );

		AtomicFileOutput out = new AtomicFileOutput( dst );
		try {
			out.getOutputStream().write( "NEW".getBytes( StandardCharsets.UTF_8 ) );
			Assumptions.assumeTrue( lockWrites( tmpDir ), "cannot deny writes in "+ tmpDir );
			assertThrows( AccessDeniedException.class, () -> out.commit() );
			tmpDir.setWritable( true );

			out.close();

			assertEquals( "OLD", read( dst ), "destination must survive a failed commit" );

			int stagedLeft = 0;
			for ( File f : tmpDir.listFiles() ) {
				if ( f.getName().startsWith( "config.dat." ) && f.getName().endsWith( ".tmp" ) )
					stagedLeft++;
			}
			assertEquals( 1, stagedLeft, "close() discarded the staged file after a failed commit" );
		}
		finally {
			tmpDir.setWritable( true );
			out.close();
		}
	}
}
