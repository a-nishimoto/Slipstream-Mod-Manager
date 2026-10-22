package net.vhati.modmanager.json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.vhati.modmanager.core.AutoUpdateInfo;
import net.vhati.modmanager.core.ComparableVersion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


public class JacksonAutoUpdateReaderTest {

	private static AutoUpdateInfo parse( Path dir, String urlsField ) throws Exception {
		String json = "{\"history_versions\":{\"1\":{\"latest\":{"
			+ "\"version\":\"1.9.1\",\"notice\":\"Update available\"" + urlsField
			+ "},\"changelog\":["
			+ "{\"version\":\"1.9.1\",\"changes\":[\"First fix\",\"Second fix\"]},"
			+ "{\"version\":\"1.9\",\"changes\":[\"Older fix\"]},"
			+ "{\"version\":\"2.0\",\"hidden\":true,\"changes\":[\"Not public\"]}"
			+ "]}}}";
		Path file = Files.writeString( dir.resolve( "update.json" ), json );
		return JacksonAutoUpdateReader.parse( file.toFile() );
	}


	@Test
	public void retainsURLsAndReleaseDetails( @TempDir Path dir ) throws Exception {
		AutoUpdateInfo info = parse( dir, ",\"urls\":{\"Windows\":\"https://example.com/win\","
			+ "\"Mac\":\"https://example.com/mac\",\"Linux\":\"https://example.com/linux\"}" );
		assertNotNull( info );
		assertEquals( Map.of( "Windows", "https://example.com/win", "Mac", "https://example.com/mac",
			"Linux", "https://example.com/linux" ), info.getLatestURLs() );
		assertEquals( new ComparableVersion( "1.9.1" ), info.getLatestVersion() );
		assertEquals( "Update available", info.getNotice() );
		assertEquals( List.of( new ComparableVersion( "1.9.1" ), new ComparableVersion( "1.9" ) ),
			List.copyOf( info.getChangelog().keySet() ) );
		assertEquals( List.of( "First fix", "Second fix" ), info.getChangelog().get( new ComparableVersion( "1.9.1" ) ) );
		assertEquals( List.of( "Older fix" ), info.getChangelog().get( new ComparableVersion( "1.9" ) ) );
	}


	@Test
	public void emptyAndNonObjectURLsRemainEmpty( @TempDir Path dir ) throws Exception {
		for ( String urls : List.of( "{}", "[]", "null", "42", "\"not an object\"" ) ) {
			AutoUpdateInfo info = parse( dir, ",\"urls\":" + urls );
			assertNotNull( info, urls );
			assertTrue( info.getLatestURLs().isEmpty(), urls );
			assertEquals( "Update available", info.getNotice(), urls );
		}
	}


	@Test
	public void missingURLsRemainNoData( @TempDir Path dir ) throws Exception {
		assertNull( parse( dir, "" ) );
	}
}
