package net.vhati.modmanager.xml;

import java.io.File;
import java.io.FileWriter;

import net.vhati.modmanager.core.ModInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;


/**
 * mod-appendix/metadata.xml comes from an untrusted zip and is parsed
 * automatically during the startup mods/ scan, with the result shown in the
 * info pane and cached to backup/cached_metadata.json. An external entity must
 * not be resolvable.
 */
public class JDOMModMetadataReaderXXETest {

	@Test
	public void externalEntitiesAreNotResolved( @TempDir File tmpDir ) throws Exception {
		File secretFile = new File( tmpDir, "secret.txt" );
		String secret = "TOP-SECRET-CANARY-VALUE";
		FileWriter writer = new FileWriter( secretFile );
		try {
			writer.write( secret );
		}
		finally {
			writer.close();
		}

		String metadataText =
			"<?xml version='1.0' encoding='UTF-8'?>\n"
			+ "<!DOCTYPE modMetadata [\n"
			+ "  <!ENTITY xxe SYSTEM \""+ secretFile.toURI() +"\">\n"
			+ "]>\n"
			+ "<modMetadata>\n"
			+ "  <title>&xxe;</title>\n"
			+ "  <threadUrl>https://example.invalid/</threadUrl>\n"
			+ "  <author>nobody</author>\n"
			+ "  <version>1.0</version>\n"
			+ "  <description>&xxe;</description>\n"
			+ "</modMetadata>\n";

		String leaked;
		try {
			ModInfo modInfo = JDOMModMetadataReader.parse( metadataText );
			leaked = modInfo.getTitle() +" / "+ modInfo.getDescription();
		}
		catch ( Exception e ) {
			// Rejecting the document outright is also an acceptable outcome.
			leaked = String.valueOf( e.getMessage() );
		}

		assertFalse( leaked.contains( secret ),
			"external entity was resolved; local file contents leaked into mod metadata" );
	}
}
