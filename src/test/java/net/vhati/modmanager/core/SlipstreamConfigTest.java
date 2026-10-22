package net.vhati.modmanager.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Config path robustness: ftl_dats_path is hand-edited and copied between
 * machines, so a loaded value is canonicalized before anything compares
 * paths -- unless it doesn't exist, which validation must report verbatim.
 */
public class SlipstreamConfigTest {

	private static Properties propsWith( String datsPath ) {
		Properties props = new Properties();
		props.setProperty( SlipstreamConfig.FTL_DATS_PATH, datsPath );
		return props;
	}

	@Test
	public void messyButExistingPathBecomesCanonical( @TempDir File tmpDir ) throws Exception {
		File sub = new File( tmpDir, "Game/data" );
		sub.mkdirs();

		String messy = new File( tmpDir, "Game/./../Game//data/." ).getPath();
		Properties props = propsWith( messy );
		SlipstreamConfig.canonicalizeDatsPath( props );

		assertEquals( sub.getCanonicalPath(), props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );
	}

	/**
	 * A copied config can name a dir through a symlink; the canonical path is
	 * the real one, and it is what every later exists()/comparison sees.
	 */
	@Test
	public void symlinkedPathResolvesToItsTarget( @TempDir File tmpDir ) throws Exception {
		Assumptions.assumeTrue( Files.getFileAttributeView( tmpDir.toPath(),
			PosixFileAttributeView.class ) != null, "symlinks need POSIX" );

		File real = new File( tmpDir, "real" );
		real.mkdirs();
		File link = new File( tmpDir, "alias" );
		Files.createSymbolicLink( link.toPath(), real.toPath() );

		Properties props = propsWith( link.getPath() );
		SlipstreamConfig.canonicalizeDatsPath( props );

		assertEquals( real.getCanonicalPath(), props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );
	}

	/**
	 * A nonexistent path must survive untouched: the reconfigure prompt should
	 * quote what the user wrote, not a normalized guess at what they meant.
	 */
	@Test
	public void nonexistentPathIsLeftVerbatim( @TempDir File tmpDir ) {
		String written = new File( tmpDir, "no/such/dir" ).getPath();
		Properties props = propsWith( written );
		SlipstreamConfig.canonicalizeDatsPath( props );

		assertEquals( written, props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );
	}

	@Test
	public void canonicalPathIsUnchanged( @TempDir File tmpDir ) throws Exception {
		File sub = new File( tmpDir, "data" );
		sub.mkdirs();
		String canonical = sub.getCanonicalPath();

		Properties props = propsWith( canonical );
		SlipstreamConfig.canonicalizeDatsPath( props );

		assertEquals( canonical, props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );
	}

	@Test
	public void absentAndBlankValuesAreLeftAlone() {
		Properties props = new Properties();
		SlipstreamConfig.canonicalizeDatsPath( props );
		assertEquals( null, props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );

		props.setProperty( SlipstreamConfig.FTL_DATS_PATH, "" );
		SlipstreamConfig.canonicalizeDatsPath( props );
		assertEquals( "", props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );

		props.setProperty( SlipstreamConfig.FTL_DATS_PATH, "   " );
		SlipstreamConfig.canonicalizeDatsPath( props );
		assertEquals( "   ", props.getProperty( SlipstreamConfig.FTL_DATS_PATH ) );
	}
}
