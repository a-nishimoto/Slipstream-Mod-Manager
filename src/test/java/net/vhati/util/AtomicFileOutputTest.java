package net.vhati.util;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
}
