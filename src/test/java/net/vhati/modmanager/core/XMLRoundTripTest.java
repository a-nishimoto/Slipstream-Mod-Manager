package net.vhati.modmanager.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Characterization tests for the XML output side.
 *
 * The valuable property here is diff-minimality: patching one tag in a 5000-line
 * FTL data file must not rewrite the other 4999 lines. That rests on
 * EmptyAwareSAXHandlerFactory, which compares the SAX Locator's line/column at
 * element start and end to tell <foo></foo> from <foo/>, and on
 * SloppyXMLOutputProcessor, which is forked from JDOM internals and can shift
 * under a JDOM upgrade without failing to compile.
 *
 * rebuildXMLFile is NOT byte-identical by design: it strips and regenerates the
 * XML declaration and forces CR-LF via EOLWriter. What must be preserved is the
 * document's content and tag forms. These tests pin both halves of that.
 */
public class XMLRoundTripTest {

	/**
	 * Models the real path: bytes come out of a game archive, decodeText sniffs
	 * their encoding, and encodeText writes them back in the target encoding
	 * that ModPatchThread picked from the archive generation.
	 *
	 * Note the source bytes are written as UTF-8 deliberately. Encoding them as
	 * the TARGET charset first would let String.getBytes() substitute '?' for
	 * anything unmappable before the code under test ever sees it, which hides
	 * exactly the behavior charactersUnmappableInWindows1252Throw asserts.
	 */
	private static String rebuild( String xml, String encoding ) throws Exception {
		InputStream in = new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) );
		InputStream out = ModUtilities.rebuildXMLFile( in, encoding, "test" );

		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int len;
		while ( (len = out.read( chunk )) >= 0 ) buf.write( chunk, 0, len );
		return new String( buf.toByteArray(), Charset.forName( encoding ) );
	}

	private static String rebuild( String xml ) throws Exception {
		return rebuild( xml, "UTF-8" );
	}


	// ---- the property the whole design exists to provide ------------------

	/**
	 * The central claim: an explicit tag pair stays a pair and a self-closing
	 * tag stays self-closing. Without this every patched file would churn in
	 * diffs and users could not tell what a mod actually changed.
	 */
	@Test
	public void emptyTagFormIsPreserved() throws Exception {
		String out = rebuild( "<FTL>\n\t<foo></foo>\n\t<bar/>\n</FTL>\n" );

		assertTrue( out.contains( "<foo></foo>" ), "tag pair was collapsed: "+ out );
		assertTrue( out.contains( "<bar />" ), "self-closing tag was expanded: "+ out );
	}

	@Test
	public void indentationAndBlankLinesSurvive() throws Exception {
		String out = rebuild( "<FTL>\n  <a>\n\n    <b>v</b>\n  </a>\n</FTL>\n" );

		assertTrue( out.contains( "\r\n  <a>\r\n\r\n    <b>v</b>\r\n  </a>\r\n" ),
			"original layout was reflowed: "+ out );
	}

	@Test
	public void attributeOrderSurvivesButQuotesAreNormalized() throws Exception {
		String out = rebuild( "<FTL>\n\t<w name=\"a\" z='1' b=\"2\">x</w>\n</FTL>\n" );

		assertTrue( out.contains( "<w name=\"a\" z=\"1\" b=\"2\">x</w>" ),
			"attribute order or quoting changed unexpectedly: "+ out );
	}


	// ---- what is deliberately normalized ----------------------------------

	@Test
	public void theDeclarationIsStrippedAndRegeneratedForTheTargetEncoding() throws Exception {
		assertTrue( rebuild( "<a>1</a>\n" ).startsWith( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" ),
			"a declaration is always added" );
		assertTrue( rebuild( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>1</a>\n", "windows-1252" )
			.startsWith( "<?xml version=\"1.0\" encoding=\"windows-1252\"?>" ),
			"the declaration follows the target encoding, not the input's" );
	}

	@Test
	public void everyLineEndingStyleConvergesOnCRLF() throws Exception {
		// FTL crashes on non-CR-LF text files, so EOLWriter forces CR-LF.
		String expected = rebuild( "<FTL>\n\t<a>1</a>\n</FTL>\n" );

		assertEquals( expected, rebuild( "<FTL>\r\n\t<a>1</a>\r\n</FTL>\r\n" ), "CRLF input" );
		assertFalse( expected.contains( "\n\n" ) && !expected.contains( "\r\n\r\n" ),
			"stray bare LF in output: "+ expected );
		assertTrue( expected.contains( "\r\n" ) );
	}

	/**
	 * DEFECT (frozen deliberately -- measured, and left alone).
	 *
	 * A trailing CR-LF is appended unconditionally, so feeding output back in
	 * grows the file by one blank line per pass. The newline comes from JDOM's
	 * inherited AbstractXMLOutputProcessor.printDocument, not from Slipstream.
	 *
	 * It does compound within a single patch run: an *.xml.append entry reads the
	 * current bytes out of the pack, so N mods appending to one innerPath leave
	 * exactly N blank lines and +2N bytes. Measured at N = 1, 5, 10 and 20;
	 * perfectly linear. Twenty mods cost forty bytes.
	 *
	 * It does NOT compound across runs. ModPatchThread restores vanilla from
	 * backups before applying anything, so repeated patching is byte-identical
	 * (confirmed by identical MD5s over four consecutive runs). The growth is
	 * bounded, not unbounded.
	 *
	 * Left unfixed on purpose. Removing it would change the trailing bytes of
	 * every XML file Slipstream writes -- so an install patched by this build
	 * would stop matching one patched by any earlier build -- and it would also
	 * collapse the blank lines that currently separate appended blocks, making
	 * merged output harder to read. Forty bytes is not worth either.
	 */
	@Test
	public void defect_rebuildIsNotIdempotent() throws Exception {
		String once = rebuild( "<FTL>\n\t<a>1</a>\n</FTL>\n" );
		String twice = rebuild( once );

		assertEquals( once + "\r\n", twice, "expected exactly one extra CRLF per pass" );
	}


	// ---- escaping ---------------------------------------------------------

	@Test
	public void bareAmpersandsAreEscapedAndExistingEntitiesAreNotDoubleEscaped() throws Exception {
		assertTrue( rebuild( "<FTL>\n\t<a>AT&T</a>\n</FTL>\n" ).contains( "AT&amp;T" ) );

		String out = rebuild( "<FTL>\n\t<a>AT&amp;T &lt;b&gt; &quot;q&quot; &apos;s&apos;</a>\n</FTL>\n" );
		assertTrue( out.contains( "AT&amp;T" ), "double-escaped: "+ out );
		assertFalse( out.contains( "&amp;amp;" ), "double-escaped: "+ out );
		// quot/apos are resolved to literal characters, which is legal and smaller.
		assertTrue( out.contains( "\"q\" 's'" ), "quote entities changed form: "+ out );
	}

	@Test
	public void cdataAndCommentsSurvive() throws Exception {
		assertTrue( rebuild( "<FTL>\n\t<a><![CDATA[x < y & z]]></a>\n</FTL>\n" )
			.contains( "<![CDATA[x < y & z]]>" ) );
		assertTrue( rebuild( "<FTL>\n\t<!-- note --><a/>\n</FTL>\n" ).contains( "<!-- note -->" ) );
	}


	// ---- encoding ---------------------------------------------------------

	@Test
	public void windows1252ContentRoundTrips() throws Exception {
		String out = rebuild( "<FTL>\n\t<a>café — über</a>\n</FTL>\n", "windows-1252" );
		assertTrue( out.contains( "café — über" ), "cp1252 content changed: "+ out );
	}

	/**
	 * A character with no windows-1252 mapping is a hard failure, not a silent
	 * substitution. Worth pinning: it is the difference between a mod author
	 * seeing an error and a user finding mojibake in-game later.
	 */
	@Test
	public void charactersUnmappableInWindows1252Throw() {
		Throwable t = assertThrows( Throwable.class,
			() -> rebuild( "<FTL>\n\t<a>中</a>\n</FTL>\n", "windows-1252" ) );

		Throwable cause = t;
		while ( cause != null && !(cause instanceof UnmappableCharacterException) ) cause = cause.getCause();
		assertTrue( cause instanceof UnmappableCharacterException,
			"expected UnmappableCharacterException, got "+ t.getClass().getName() +": "+ t.getMessage() );
	}

	@Test
	public void aUTF8BOMIsConsumedAndNotReEmitted() throws Exception {
		byte[] withBom = new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF};
		byte[] body = "<a>1</a>\n".getBytes( StandardCharsets.UTF_8 );
		byte[] all = new byte[withBom.length + body.length];
		System.arraycopy( withBom, 0, all, 0, withBom.length );
		System.arraycopy( body, 0, all, withBom.length, body.length );

		InputStream out = ModUtilities.rebuildXMLFile( new ByteArrayInputStream( all ), "UTF-8", "test" );
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int len;
		while ( (len = out.read( chunk )) >= 0 ) buf.write( chunk, 0, len );
		byte[] result = buf.toByteArray();

		assertFalse( result.length > 2 && result[0] == (byte)0xEF && result[1] == (byte)0xBB,
			"BOM was re-emitted" );
		assertTrue( new String( result, StandardCharsets.UTF_8 ).contains( "<a>1</a>" ) );
	}
}
