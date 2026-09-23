package net.vhati.ftldat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The legacy .dat format, used by every FTL install from 1.01 to 1.5.13.
 *
 * Its sibling PkgPack turned out to corrupt archives when a patch aborted before
 * repack(). These round trips confirm FTLPack does not share that defect and
 * keep it that way -- those installs have TWO dats, so the window for a partial
 * write is wider, not narrower.
 */
public class FTLPackTest {

	private static InputStream bytes( String s ) {
		return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
	}

	private static byte[] readAll( InputStream is ) throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int len;
		while ( (len = is.read( chunk )) >= 0 ) buf.write( chunk, 0, len );
		is.close();
		return buf.toByteArray();
	}

	private static void assertPackMatches( File datFile, Map<String,String> expected ) throws Exception {
		FTLPack pack = new FTLPack( datFile, "r" );
		try {
			assertEquals( expected.size(), pack.list().size(), "entry count" );
			for ( Map.Entry<String,String> e : expected.entrySet() ) {
				assertTrue( pack.contains( e.getKey() ), "missing innerPath: "+ e.getKey() );
				assertArrayEquals( e.getValue().getBytes( StandardCharsets.UTF_8 ),
					readAll( pack.getInputStream( e.getKey() ) ),
					"content differs for: "+ e.getKey() );
			}
		}
		finally {
			pack.close();
		}
	}

	private static Map<String,String> seed( FTLPack pack, int count ) throws Exception {
		Map<String,String> expected = new LinkedHashMap<String,String>();
		for ( int i=0; i < count; i++ ) {
			String innerPath = "data/file"+ i +".xml";
			String content = "<vanilla id='"+ i +"'>"+ i +"</vanilla>";
			expected.put( innerPath, content );
			pack.add( innerPath, bytes( content ) );
		}
		return expected;
	}


	@Test
	public void addedEntriesSurviveWithoutRepack( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "data.dat" );
		Map<String,String> expected;

		FTLPack pack = new FTLPack( datFile, "w+", 50 );
		try {
			expected = seed( pack, 20 );
		}
		finally {
			pack.close();  // No repack(), as ModPatchThread's finally block does.
		}
		assertPackMatches( datFile, expected );
	}


	@Test
	public void entriesSurviveIndexGrowth( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "data.dat" );
		Map<String,String> expected;

		FTLPack pack = new FTLPack( datFile, "w+", 2 );
		try {
			expected = seed( pack, 120 );
		}
		finally {
			pack.close();
		}
		assertPackMatches( datFile, expected );
	}


	/** An aborted patch must leave the archive readable. */
	@Test
	public void abortedPatchLeavesArchiveReadable( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "data.dat" );
		Map<String,String> expected;

		FTLPack vanilla = new FTLPack( datFile, "w+", 250 );
		try {
			expected = seed( vanilla, 200 );
			vanilla.repack();
		}
		finally {
			vanilla.close();
		}
		assertPackMatches( datFile, expected );

		FTLPack modded = new FTLPack( datFile, "r+" );
		try {
			for ( int i=0; i < 5; i++ ) {
				String innerPath = "data/file"+ (i*37) +".xml";
				String content = "<patched>MODDED"+ i +"</patched>";
				modded.remove( innerPath );
				modded.add( innerPath, bytes( content ) );
				expected.put( innerPath, content );
			}
		}
		finally {
			modded.close();  // Aborted: repack() never reached.
		}
		assertPackMatches( datFile, expected );
	}


	/**
	 * extractTo never decremented bytesRemaining, so it re-read and re-wrote the
	 * entry's first 4096 bytes forever, until the caller's sink or the disk gave
	 * out. It had no callers, which is the only reason nobody hit it.
	 */
	@Test
	@Timeout( 15 )
	public void extractToTerminatesAndWritesExactlyTheEntry( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "data.dat" );

		// Bigger than the 4096-byte buffer, so the loop must iterate.
		StringBuilder body = new StringBuilder();
		while ( body.length() < 10000 ) body.append( "0123456789" );
		String content = body.toString();

		FTLPack pack = new FTLPack( datFile, "w+", 10 );
		try {
			pack.add( "data/big.xml", bytes( content ) );
			pack.repack();
		}
		finally {
			pack.close();
		}

		FTLPack reopened = new FTLPack( datFile, "r" );
		try {
			ByteArrayOutputStream sink = new ByteArrayOutputStream();
			reopened.extractTo( "data/big.xml", sink );

			assertArrayEquals( content.getBytes( StandardCharsets.UTF_8 ), sink.toByteArray(),
				"extractTo did not reproduce the entry exactly" );
		}
		finally {
			reopened.close();
		}
	}


	/**
	 * A failed open must not keep the file handle.
	 *
	 * Indexing reads the archive and throws on a corrupt one; the
	 * RandomAccessFile used to stay open. POSIX hides this -- an unlinked file
	 * with a live handle still disappears -- but Windows LOCKS it, so the dat
	 * could not be deleted or replaced afterward. Found by CI on Windows, where
	 * JUnit could not clean up its own @TempDir.
	 *
	 * Deleting is the portable proxy for "no handle is held": it always succeeds
	 * on POSIX, so this assertion only really bites on Windows.
	 */
	@Test
	public void aFailedOpenReleasesTheFileHandle( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "corrupt.dat" );
		java.nio.file.Files.write( datFile.toPath(), "this is not an archive".getBytes( StandardCharsets.UTF_8 ) );

		assertThrows( Exception.class, () -> new FTLPack( datFile, "r" ) );

		assertTrue( datFile.delete(),
			"the file is still locked, so the failed open leaked its handle" );
	}
}
