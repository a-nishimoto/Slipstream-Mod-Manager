package net.vhati.ftldat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Round-trip tests for the PKG archive format used by FTL 1.6.1+.
 *
 * These deliberately close() WITHOUT calling repack(). repack() rebuilds the
 * entry table and paths region wholesale from memory, so it masks any defect
 * in the incremental add() path. ModPatchThread only reaches repack() on the
 * success path -- its finally block closes and nothing more -- so a cancelled
 * or failed patch ships exactly the state these tests assert on.
 */
public class PkgPackRoundTripTest {

	private static InputStream bytes( String s ) {
		return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
	}

	private static byte[] readAll( InputStream is ) throws Exception {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int len;
		while ( (len = is.read( buf )) >= 0 ) bo.write( buf, 0, len );
		is.close();
		return bo.toByteArray();
	}

	private static void assertPackMatches( File datFile, Map<String,String> expected ) throws Exception {
		PkgPack pack = new PkgPack( datFile, "r" );
		try {
			assertEquals( expected.size(), pack.list().size(), "entry count" );

			for ( Map.Entry<String,String> e : expected.entrySet() ) {
				assertTrue( pack.contains( e.getKey() ), "missing innerPath: "+ e.getKey() );
				assertArrayEquals(
					e.getValue().getBytes( StandardCharsets.UTF_8 ),
					readAll( pack.getInputStream( e.getKey() ) ),
					"content differs for innerPath: "+ e.getKey() );
			}
		}
		finally {
			pack.close();
		}
	}


	/**
	 * A fresh pack must be readable after adds, with no repack().
	 */
	@Test
	public void addedEntriesSurviveWithoutRepack( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "ftl.dat" );
		Map<String,String> expected = new LinkedHashMap<String,String>();

		PkgPack pack = new PkgPack( datFile, "w+", 10 );
		try {
			for ( int i=0; i < 5; i++ ) {
				String innerPath = "data/file"+ i +".xml";
				String content = "<vanilla id='"+ i +"'/>";
				expected.put( innerPath, content );
				pack.add( innerPath, bytes( content ) );
			}
		}
		finally {
			pack.close();  // No repack(), as ModPatchThread's finally block does.
		}

		assertPackMatches( datFile, expected );
	}


	/**
	 * Adding past the initially allocated slots forces growIndex(), which
	 * relocates the paths region. The existing entries must come back intact.
	 */
	@Test
	public void entriesSurviveIndexGrowth( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "ftl.dat" );
		Map<String,String> expected = new LinkedHashMap<String,String>();

		PkgPack pack = new PkgPack( datFile, "w+", 2 );
		try {
			for ( int i=0; i < 120; i++ ) {
				String innerPath = "data/some/longer/path/to/file"+ i +".xml";
				String content = "<vanilla id='"+ i +"'>"+ i +"</vanilla>";
				expected.put( innerPath, content );
				pack.add( innerPath, bytes( content ) );
			}
		}
		finally {
			pack.close();
		}

		assertPackMatches( datFile, expected );
	}


	/**
	 * The scenario that actually reaches users: an existing archive is opened
	 * "r+", some entries are replaced the way ModPatchThread does it, and the
	 * run is then aborted before repack().
	 */
	@Test
	public void abortedPatchLeavesArchiveReadable( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "ftl.dat" );
		Map<String,String> expected = new LinkedHashMap<String,String>();

		PkgPack vanilla = new PkgPack( datFile, "w+", 450 );
		try {
			for ( int i=0; i < 400; i++ ) {
				String innerPath = "data/file"+ i +".xml";
				String content = "<vanilla id='"+ i +"'>"+ i +"</vanilla>";
				expected.put( innerPath, content );
				vanilla.add( innerPath, bytes( content ) );
			}
			vanilla.repack();
		}
		finally {
			vanilla.close();
		}
		assertPackMatches( datFile, expected );

		PkgPack modded = new PkgPack( datFile, "r+" );
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
	 * A corrupt paths region must surface as an IOException the callers can
	 * catch, not a RuntimeException from the error message itself.
	 */
	@Test
	public void corruptPathsRegionReportsIOException( @TempDir File tmpDir ) throws Exception {
		File datFile = new File( tmpDir, "ftl.dat" );

		PkgPack pack = new PkgPack( datFile, "w+", 10 );
		try {
			pack.add( "data/file0.xml", bytes( "<vanilla/>" ) );
			pack.repack();
		}
		finally {
			pack.close();
		}

		// Scribble a non-ASCII byte over the start of the paths region, which
		// sits immediately after the entry table. repack() drops vacant slots,
		// so read the surviving entry count out of the header rather than
		// assuming the count the pack was created with.
		java.io.RandomAccessFile raf = new java.io.RandomAccessFile( datFile, "rw" );
		try {
			raf.seek( 8 );  // Skip the signature, headerSize and entrySize.
			long entryCount = raf.readInt() & 0xFFFFFFFFL;
			raf.seek( 16 + entryCount * 20 );
			raf.write( 0xE9 );
		}
		finally {
			raf.close();
		}

		try {
			new PkgPack( datFile, "r" ).close();
		}
		catch ( java.io.IOException e ) {
			return;  // Expected.
		}
		catch ( RuntimeException e ) {
			throw new AssertionError( "Corrupt archive raised a RuntimeException callers cannot catch: "+ e, e );
		}
	}
}
