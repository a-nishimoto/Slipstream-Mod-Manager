package net.vhati.ftldat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * FolderPack is the sink for "Extract Dats". Its innerPaths originate in
 * untrusted mod archives, which can plant traversing paths into a dat during
 * patching, so containment has to hold against "..".
 */
public class FolderPackContainmentTest {

	private static InputStream bytes( String s ) {
		return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
	}


	@Test
	public void ordinaryInnerPathsAreAccepted( @TempDir File tmpDir ) throws Exception {
		File rootDir = new File( tmpDir, "extract" );
		assertTrue( rootDir.mkdirs() );
		FolderPack pack = new FolderPack( rootDir );

		assertEquals( new File( rootDir, "data/events.xml" ).getCanonicalPath(),
			pack.getFile( "data/events.xml" ).getCanonicalPath() );
		assertEquals( new File( rootDir, "top.txt" ).getCanonicalPath(),
			pack.getFile( "top.txt" ).getCanonicalPath() );
	}


	@Test
	public void traversingInnerPathsAreRejected( @TempDir File tmpDir ) throws Exception {
		File rootDir = new File( tmpDir, "extract" );
		assertTrue( rootDir.mkdirs() );
		assertTrue( new File( rootDir, "data" ).mkdirs() );
		FolderPack pack = new FolderPack( rootDir );

		String[] evilPaths = new String[] {
			"../evil.txt",
			"data/../../evil.txt",
			"data/../../../../Library/LaunchAgents/evil.plist",
			"..",
		};

		for ( String innerPath : evilPaths ) {
			assertThrows( IllegalArgumentException.class,
				() -> pack.getFile( innerPath ),
				"escaped the FolderPack: "+ innerPath );
		}

		assertThrows( IllegalArgumentException.class, () -> pack.getFile( "data\\evil.txt" ) );
	}


	/**
	 * DatExtractDialog and SlipstreamCLI both call contains()/remove() before
	 * add(). All three route through getFile(), so an unguarded traversal is an
	 * arbitrary-file overwrite, not merely an arbitrary-file create.
	 */
	@Test
	public void traversalCannotOverwriteAnExistingFileOutsideRoot( @TempDir File tmpDir ) throws Exception {
		File rootDir = new File( tmpDir, "extract" );
		assertTrue( rootDir.mkdirs() );
		assertTrue( new File( rootDir, "data" ).mkdirs() );

		File victimDir = new File( tmpDir, "victim" );
		assertTrue( victimDir.mkdirs() );
		File victim = new File( victimDir, "secret.txt" );
		FileWriter writer = new FileWriter( victim );
		try {
			writer.write( "ORIGINAL" );
		}
		finally {
			writer.close();
		}

		FolderPack pack = new FolderPack( rootDir );
		String evilPath = "data/../../victim/secret.txt";

		assertThrows( IllegalArgumentException.class, () -> pack.contains( evilPath ) );
		assertThrows( IllegalArgumentException.class, () -> pack.remove( evilPath ) );
		assertThrows( IllegalArgumentException.class, () -> pack.add( evilPath, bytes( "PWNED" ) ) );

		assertTrue( victim.exists(), "victim was deleted" );
		assertEquals( "ORIGINAL",
			new String( java.nio.file.Files.readAllBytes( victim.toPath() ), StandardCharsets.UTF_8 ),
			"victim was overwritten" );
	}


	/**
	 * add() calls mkdirs(), so a traversal does not need the escape path to
	 * already exist on disk.
	 */
	@Test
	public void traversalCannotCreateDirectoriesOutsideRoot( @TempDir File tmpDir ) throws Exception {
		File rootDir = new File( tmpDir, "extract" );
		assertTrue( rootDir.mkdirs() );
		assertTrue( new File( rootDir, "data" ).mkdirs() );
		FolderPack pack = new FolderPack( rootDir );

		assertThrows( IllegalArgumentException.class,
			() -> pack.add( "data/../../brandnew/sub/dir/evil.plist", bytes( "<plist/>" ) ) );

		assertFalse( new File( tmpDir, "brandnew" ).exists(), "directories were created outside the root" );
	}
}
