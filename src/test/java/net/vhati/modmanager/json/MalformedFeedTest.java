package net.vhati.modmanager.json;

import java.io.File;
import java.io.FileWriter;

import net.vhati.modmanager.core.AutoUpdateInfo;
import net.vhati.modmanager.core.ModDB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * The catalog and auto-update feeds are downloaded over the network and written
 * straight to disk with no temp file, so a truncated or interrupted download
 * leaves a short or zero-byte file that is parsed on the next launch.
 *
 * Both readers must degrade to null rather than throw. They are called from
 * ManagerInitThread, which catches Exception only -- an escaping throwable kills
 * the init thread, and at the local-cache call site that happens before the mod
 * scan runs, so the app comes up with an empty mod list and no explanation.
 *
 * Note this is not purely hypothetical bookkeeping: Jackson 2.15+ changed
 * readTree() on empty input to return MissingNode rather than throw, which moved
 * empty-file handling out from under the JsonProcessingException catch.
 */
public class MalformedFeedTest {

	private static File write( File dir, String name, String content ) throws Exception {
		File f = new File( dir, name );
		FileWriter w = new FileWriter( f );
		try {
			w.write( content );
		}
		finally {
			w.close();
		}
		return f;
	}

	private static String deeplyNested() {
		StringBuilder buf = new StringBuilder();
		for ( int i=0; i < 20000; i++ ) buf.append( '[' );
		for ( int i=0; i < 20000; i++ ) buf.append( ']' );
		return buf.toString();
	}


	@Test
	public void catalogReaderSurvivesMalformedFeeds( @TempDir File tmpDir ) throws Exception {
		assertNull( JacksonCatalogReader.parse( write( tmpDir, "empty.json", "" ) ),
			"empty file" );
		assertNull( JacksonCatalogReader.parse( write( tmpDir, "blank.json", "   \n\t " ) ),
			"whitespace only" );
		assertNull( JacksonCatalogReader.parse( write( tmpDir, "shape.json", "{}" ) ),
			"valid json, wrong shape" );
		assertNull( JacksonCatalogReader.parse( write( tmpDir, "cut.json", "{\"catalog_versions\":" ) ),
			"truncated mid-token" );
		assertNull( JacksonCatalogReader.parse( write( tmpDir, "nested.json", deeplyNested() ) ),
			"deeply nested (CVE-2020-36518 / CVE-2025-52999 guard)" );
	}


	@Test
	public void autoUpdateReaderSurvivesMalformedFeeds( @TempDir File tmpDir ) throws Exception {
		assertNull( JacksonAutoUpdateReader.parse( write( tmpDir, "empty.json", "" ) ),
			"empty file" );
		assertNull( JacksonAutoUpdateReader.parse( write( tmpDir, "blank.json", "   \n\t " ) ),
			"whitespace only" );
		assertNull( JacksonAutoUpdateReader.parse( write( tmpDir, "shape.json", "{}" ) ),
			"valid json, wrong shape" );
		assertNull( JacksonAutoUpdateReader.parse( write( tmpDir, "cut.json", "{\"history_versions\":" ) ),
			"truncated mid-token" );
		assertNull( JacksonAutoUpdateReader.parse( write( tmpDir, "nested.json", deeplyNested() ) ),
			"deeply nested" );
	}
}
