package net.vhati.modmanager.core;

import java.util.List;

import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.Text;
import org.jdom2.input.JDOMParseException;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Characterization tests for the fallback parser.
 *
 * FTL's own game data is not well-formed XML, and neither are many mods, so
 * ModUtilities.parseStrictOrSloppyXML tries a real SAXBuilder and drops to this
 * regex tokenizer when that throws -- silently, with a bare "// Ignore the
 * error". Whatever this class does IS what FTL modding does for every malformed
 * file, and nobody ever learns the fallback happened.
 *
 * These tests FREEZE current behavior rather than assert desired behavior. It is
 * a first-match-wins loop over nine regexes; editing any one of them silently
 * reshapes every malformed file it touches, and there was previously nothing to
 * notice that. Cases named "defect" pin behavior that is wrong on purpose, so
 * fixing it later is a deliberate, visible change rather than an accident.
 */
public class SloppyXMLParserTest {

	private static Document parse( String xml ) throws Exception {
		return new SloppyXMLParser().build( xml );
	}

	private static String textOf( String xml ) throws Exception {
		return parse( xml ).getRootElement().getText();
	}

	/** Renders tree shape compactly: element names, and text nodes with their length. */
	private static String shape( Element e ) {
		StringBuilder buf = new StringBuilder();
		buf.append( e.getName() ).append( "[" );
		for ( Content c : e.getContent() ) {
			if ( c instanceof Element ) buf.append( shape( (Element)c ) );
			else if ( c instanceof Text ) buf.append( "text(" ).append( ((Text)c).getText().length() ).append( ")" );
			else buf.append( c.getCType() );
		}
		return buf.append( "]" ).toString();
	}


	// ---- the sloppiness this class exists for -----------------------------

	@Test
	public void bareAmpersandsSurviveAsLiteralText() throws Exception {
		assertEquals( "Fish & Chips", textOf( "<a>Fish & Chips</a>" ) );
		assertEquals( "AT&T rocks", textOf( "<a>AT&T rocks</a>" ) );
	}

	@Test
	public void unterminatedEntitiesAreLeftAlone() throws Exception {
		// entityPtn requires a ';', so "&lt" without one is not an entity.
		assertEquals( "5 &lt 6 &gt 4;", textOf( "<a>5 &lt 6 &gt 4;</a>" ) );
		assertEquals( "&;", textOf( "<a>&;</a>" ) );
	}

	@Test
	public void unknownEntitiesPassThroughVerbatim() throws Exception {
		assertEquals( "&notanentity;", textOf( "<a>&notanentity;</a>" ) );
		assertEquals( "&notanentity; x",
			parse( "<a t=\"&notanentity; x\"/>" ).getRootElement().getAttributeValue( "t" ) );
	}

	@Test
	public void thePredefinedEntitiesAreResolved() throws Exception {
		assertEquals( "<>&'\"", textOf( "<a>&lt;&gt;&amp;&apos;&quot;</a>" ) );
	}

	@Test
	public void multipleRootElementsAreKeptUnderASyntheticWrapper() throws Exception {
		// FTL's data files genuinely have several roots. A single root is
		// promoted instead, so downstream code sees "wrapper" only sometimes.
		assertEquals( "wrapper[a[]b[]]", shape( parse( "<a/><b/>" ).getRootElement() ) );
		assertEquals( "a[b[]text(0)]", shape( parse( "<a><b/></a>" ).getRootElement() ) );
	}

	@Test
	public void anyCloseTagClosesTheOpenElementRegardlessOfName() throws Exception {
		// Documented sloppiness: the name in the end tag is captured and dropped.
		assertEquals( "a[text(0)]", shape( parse( "<a></b>" ).getRootElement() ) );
	}

	@Test
	public void anUnclosedTagAtEndOfInputIsAHardFailure() {
		assertThrows( JDOMParseException.class, () -> parse( "<a><b>text" ) );
	}


	// ---- the empty-tag distinction the output side depends on -------------

	/**
	 * An explicit close injects a zero-length Text node; a self-closing tag does
	 * not. That difference is not incidental -- it is how the original tag form
	 * survives to SloppyXMLOutputProcessor, so <foo></foo> is not rewritten as
	 * <foo/> in files a mod never touched.
	 */
	@Test
	public void explicitCloseTagsCarryAnEmptyTextNodeAndSelfClosingOnesDoNot() throws Exception {
		assertEquals( "r[a[]text(0)]", shape( parse( "<r><a/></r>" ).getRootElement() ) );
		assertEquals( "r[a[text(0)]text(0)]", shape( parse( "<r><a></a></r>" ).getRootElement() ) );
	}


	// ---- previously frozen defects, now fixed -----------------------------

	/**
	 * Character references above U+FFFF used to be truncated by a (char)
	 * narrowing cast: &#x1F600; silently became U+F600, a Private Use
	 * codepoint, and the author saw mojibake in-game with no warning.
	 */
	@Test
	public void astralCharacterReferencesSurviveIntact() throws Exception {
		assertEquals( 0x1F600, textOf( "<a>&#x1F600;</a>" ).codePointAt( 0 ), "hex form" );
		assertEquals( 0x1F600, textOf( "<a>&#128512;</a>" ).codePointAt( 0 ), "decimal form" );

		// Below U+FFFF is unchanged.
		assertEquals( "\u263A", textOf( "<a>&#x263A;</a>" ) );
		assertEquals( "AA", textOf( "<a>&#65;&#x41;</a>" ) );
	}

	/**
	 * A reference that cannot become a legal XML character is passed through as
	 * literal text, the way an unknown named entity already was.
	 *
	 * &#0; is a valid codepoint but illegal in XML, and it used to reach JDOM and
	 * throw. Salvaging the text instead keeps the mod usable and shows the author
	 * exactly what they wrote; the ampersand is escaped on output.
	 */
	@Test
	public void unrepresentableCharacterReferencesAreKeptAsLiteralText() throws Exception {
		assertEquals( "&#0;", textOf( "<a>&#0;</a>" ), "illegal in XML" );
		assertEquals( "&#99999999999;", textOf( "<a>&#99999999999;</a>" ), "overflows an int" );
		assertEquals( "&#xFFFFFFF;", textOf( "<a>&#xFFFFFFF;</a>" ), "not a valid codepoint" );
	}

	/**
	 * The reserved "xml" prefix is bound implicitly in every document, and JDOM
	 * refuses to bind it to anything else. Building a placeholder namespace from
	 * it threw IllegalNameException out of the middle of parsing.
	 *
	 * This was not cosmetic: a mod that was both malformed (so strict parsing
	 * failed and this parser ran) and used xml:space aborted the whole patch run.
	 */
	@Test
	public void theReservedXmlPrefixIsAccepted() throws Exception {
		assertEquals( "preserve",
			parse( "<a xml:space=\"preserve\">x</a>" ).getRootElement()
				.getAttributeValue( "space", Namespace.XML_NAMESPACE ) );
		assertEquals( "en",
			parse( "<a xml:lang=\"en\">x</a>" ).getRootElement()
				.getAttributeValue( "lang", Namespace.XML_NAMESPACE ) );

		// xml:-prefixed element names too.
		assertEquals( "foo", parse( "<xml:foo>x</xml:foo>" ).getRootElement().getName() );
	}

	/**
	 * Declaring xmlns:xml is itself illegal, so the fix must not emit one --
	 * otherwise it would only move the exception to serialization time.
	 */
	@Test
	public void theXmlPrefixIsNotRedeclaredOnOutput() throws Exception {
		String out = new XMLOutputter( Format.getCompactFormat() )
			.outputString( parse( "<a xml:space=\"preserve\">x</a>" ).getRootElement() );

		assertTrue( out.contains( "xml:space=\"preserve\"" ), out );
		assertFalse( out.contains( "xmlns:xml" ), "an illegal xmlns:xml declaration was emitted: "+ out );

		// An ordinary prefix still gets its placeholder declaration.
		String other = new XMLOutputter( Format.getCompactFormat() )
			.outputString( parse( "<a mod:z=\"1\">x</a>" ).getRootElement() );
		assertTrue( other.contains( "xmlns:mod" ), other );
	}

	/**
	 * build() declares "throws JDOMParseException". It now keeps that promise:
	 * JDOM's Illegal*Exception family and NumberFormatException all extend
	 * IllegalArgumentException and are wrapped, so callers such as
	 * ModUtilities.parseStrictOrSloppyXML -- which catches only
	 * JDOMParseException -- can actually handle a bad file.
	 */
	@Test
	public void unparseableInputRaisesACheckedJDOMParseException() {
		assertThrows( JDOMParseException.class, () -> parse( "<a><b>text" ) );
	}

	@Test
	public void previouslyUncatchableInputsNoLongerEscapeParsing() throws Exception {
		// Both of these used to escape as unchecked JDOM exceptions.
		ModUtilities.parseStrictOrSloppyXML( "<a>&#0;</a>", "test" );
		ModUtilities.parseStrictOrSloppyXML( "<a xml:space=\"preserve\">x</a>", "test" );
	}
}
