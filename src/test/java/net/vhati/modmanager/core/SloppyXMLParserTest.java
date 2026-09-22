package net.vhati.modmanager.core;

import java.util.List;

import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Text;
import org.jdom2.input.JDOMParseException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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


	// ---- defects, frozen deliberately -------------------------------------

	/**
	 * DEFECT (frozen): character references above U+FFFF are truncated by a
	 * (char) narrowing cast, silently producing a different character.
	 *
	 * U+1F600 becomes U+F600, a Private Use codepoint. There is no warning, and
	 * the mod author sees mojibake in-game. The fix is Character.toChars(); this
	 * test exists so making it is a visible decision.
	 */
	@Test
	public void defect_astralCharacterReferencesAreSilentlyTruncated() throws Exception {
		assertEquals( "", textOf( "<a>&#x1F600;</a>" ), "expected the truncated U+F600" );
		assertEquals( "ǐ", textOf( "<a>&#66000;</a>" ) );

		// Below U+FFFF is handled correctly.
		assertEquals( "☺", textOf( "<a>&#x263A;</a>" ) );
		assertEquals( "AA", textOf( "<a>&#65;&#x41;</a>" ) );
	}

	/**
	 * DEFECT (frozen): build() declares "throws JDOMParseException", but some
	 * inputs escape as unchecked JDOM exceptions instead.
	 *
	 * ModUtilities.parseStrictOrSloppyXML only catches JDOMParseException around
	 * the sloppy build, so these propagate out of mod parsing entirely rather
	 * than being reported as a bad mod file.
	 */
	@Test
	public void defect_someInputsEscapeAsUncheckedExceptions() {
		Throwable nul = assertThrows( Throwable.class, () -> parse( "<a>&#0;</a>" ) );
		assertEquals( "org.jdom2.IllegalDataException", nul.getClass().getName() );
		assertTrue( nul instanceof RuntimeException, "unchecked, so callers cannot catch it by contract" );

		// xml:space="preserve" is valid, common XML that trips the same hole.
		Throwable xmlns = assertThrows( Throwable.class, () -> parse( "<a xml:space=\"preserve\">x</a>" ) );
		assertEquals( "org.jdom2.IllegalNameException", xmlns.getClass().getName() );
		assertTrue( xmlns instanceof RuntimeException );
	}

	/** And the same escape happens through the real entry point mods go via. */
	@Test
	public void defect_uncheckedExceptionsEscapeParseStrictOrSloppyXML() {
		Throwable t = assertThrows( Throwable.class,
			() -> ModUtilities.parseStrictOrSloppyXML( "<a>&#0;</a>", "test" ) );
		assertTrue( t instanceof RuntimeException,
			"escaped as "+ t.getClass().getName() +", which callers do not expect" );
	}
}
