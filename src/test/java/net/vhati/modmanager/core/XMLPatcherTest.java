package net.vhati.modmanager.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Covers the mod XML patch DSL, which had no tests at all.
 *
 * Documents are wrapped the way ModUtilities.patchXMLFile wraps them, so the
 * mod namespaces resolve and multiple roots are legal.
 */
public class XMLPatcherTest {

	private static final String WRAP_OPEN =
		"<wrapper xmlns:mod='mod' xmlns:mod-append='mod-append' xmlns:mod-overwrite='mod-overwrite'>";
	private static final String WRAP_CLOSE = "</wrapper>";

	private static Document parse( String body ) throws Exception {
		SAXBuilder builder = XMLSecurity.newSecureSAXBuilder();
		return builder.build( new StringReader( WRAP_OPEN + body + WRAP_CLOSE ) );
	}

	private static String patchToString( String main, String append, List<String> warningsOut ) throws Exception {
		XMLPatcher patcher = new XMLPatcher();
		if ( warningsOut != null ) {
			patcher.setWarningListener( message -> warningsOut.add( message ) );
		}
		Document merged = patcher.patch( parse( main ), parse( append ) );

		XMLOutputter outputter = new XMLOutputter( Format.getCompactFormat() );
		StringBuilder buf = new StringBuilder();
		for ( Element e : merged.getRootElement().getChildren() ) {
			buf.append( outputter.outputString( e ) );
		}
		return buf.toString();
	}

	private static String patch( String main, String append ) throws Exception {
		return patchToString( main, append, null );
	}


	// ---- <mod:par op="AND"> must be a true intersection -------------------

	/**
	 * The defect: candidateSet.isEmpty() was used to mean "first criterion".
	 * Once an AND intersection emptied, the NEXT criterion re-seeded the set
	 * from scratch, so the result could contain nodes matching only that one.
	 *
	 * Here criterion 1 (colour=red) and criterion 2 (shape=square) share
	 * nothing, and criterion 3 (size=big) matches something. A true AND is
	 * empty; the old code returned the size=big node and edited it.
	 */
	@Test
	public void andWithAnEmptyIntersectionStaysEmpty() throws Exception {
		String main = "<item colour='red' name='A'/>"
			+ "<item shape='square' name='B'/>"
			+ "<item size='big' name='C'/>";
		String append = "<mod:findComposite>"
			+ "<mod:par op='AND'>"
			+ "<mod:findLike type='item'><mod:selector colour='red'/></mod:findLike>"
			+ "<mod:findLike type='item'><mod:selector shape='square'/></mod:findLike>"
			+ "<mod:findLike type='item'><mod:selector size='big'/></mod:findLike>"
			+ "</mod:par>"
			+ "<mod:setAttributes touched='yes'/>"
			+ "</mod:findComposite>";

		String result = patchWholeRoot( main, append );
		assertTrue( !result.contains( "touched" ),
			"AND matched something although the intersection was empty: "+ result );
	}

	/** A genuine intersection still matches, so the fix did not just break AND. */
	@Test
	public void andReturnsTheSharedMatch() throws Exception {
		String main = "<item colour='red' size='big' name='A'/>"
			+ "<item colour='red' size='small' name='B'/>";
		String append = "<mod:findComposite>"
			+ "<mod:par op='AND'>"
			+ "<mod:findLike type='item'><mod:selector colour='red'/></mod:findLike>"
			+ "<mod:findLike type='item'><mod:selector size='big'/></mod:findLike>"
			+ "</mod:par>"
			+ "<mod:setAttributes touched='yes'/>"
			+ "</mod:findComposite>";

		String result = patchWholeRoot( main, append );
		assertTrue( result.contains( "name=\"A\" touched=\"yes\"" ) || result.contains( "touched=\"yes\"" ),
			"AND did not match the shared node: "+ result );
		assertEquals( 1, countOccurrences( result, "touched=" ),
			"AND touched more than the intersection: "+ result );
	}

	/** OR is unaffected by the fix. */
	@Test
	public void orStillUnions() throws Exception {
		String main = "<item colour='red' name='A'/>"
			+ "<item size='big' name='B'/>"
			+ "<item name='C'/>";
		String append = "<mod:findComposite>"
			+ "<mod:par op='OR'>"
			+ "<mod:findLike type='item'><mod:selector colour='red'/></mod:findLike>"
			+ "<mod:findLike type='item'><mod:selector size='big'/></mod:findLike>"
			+ "</mod:par>"
			+ "<mod:setAttributes touched='yes'/>"
			+ "</mod:findComposite>";

		String result = patchWholeRoot( main, append );
		assertEquals( 2, countOccurrences( result, "touched=" ),
			"OR should have matched exactly the union: "+ result );
	}


	// ---- <mod:setValue> discarding children -------------------------------

	/**
	 * setValue uses JDOM setText(), which drops every child element. The
	 * behavior is kept (mods rely on it) but must no longer be silent.
	 */
	@Test
	public void setValueWarnsWhenItDiscardsChildren() throws Exception {
		String main = "<holder><keepMe/><alsoMe/>old</holder>";
		String append = "<mod:findLike type='holder'>"
			+ "<mod:setValue>new</mod:setValue>"
			+ "</mod:findLike>";

		List<String> warnings = new ArrayList<String>();
		patchToString( main, append, warnings );

		assertEquals( 1, warnings.size(), "expected exactly one warning, got: "+ warnings );
		assertTrue( warnings.get( 0 ).contains( "2 child tag(s)" ),
			"warning should say how much was discarded: "+ warnings.get( 0 ) );
	}

	@Test
	public void setValueOnALeafWarnsAboutNothing() throws Exception {
		String main = "<holder>old</holder>";
		String append = "<mod:findLike type='holder'>"
			+ "<mod:setValue>new</mod:setValue>"
			+ "</mod:findLike>";

		List<String> warnings = new ArrayList<String>();
		String result = patchToString( main, append, warnings );

		assertTrue( warnings.isEmpty(), "unexpected warnings: "+ warnings );
		assertTrue( result.contains( ">new<" ), "value was not set: "+ result );
	}


	// ---- a top-level find that matches nothing ---------------------------

	/**
	 * The commonest authoring mistake: the outermost find matches nothing, so
	 * every command under it is skipped and the mod appears to do nothing.
	 * panic="true" would make it an error; by default it was entirely silent.
	 */
	@Test
	public void aTopLevelFindThatMatchesNothingWarns() throws Exception {
		String main = "<holder>old</holder>";
		String append = "<mod:findLike type='nosuchtag'>"
			+ "<mod:setValue>new</mod:setValue>"
			+ "</mod:findLike>";

		List<String> warnings = new ArrayList<String>();
		String result = patchToString( main, append, warnings );

		assertEquals( 1, warnings.size(), "expected one warning, got: "+ warnings );
		assertTrue( warnings.get( 0 ).contains( "matched nothing" ), warnings.get( 0 ) );
		assertTrue( result.contains( "old" ), "document should be untouched: "+ result );
	}

	/** A nested find coming up empty is legitimate and must stay quiet. */
	@Test
	public void aNestedFindThatMatchesNothingIsQuiet() throws Exception {
		String main = "<holder><inner/></holder>";
		String append = "<mod:findLike type='holder'>"
			+ "<mod:findLike type='nosuchtag'>"
			+ "<mod:setValue>new</mod:setValue>"
			+ "</mod:findLike>"
			+ "</mod:findLike>";

		List<String> warnings = new ArrayList<String>();
		patchToString( main, append, warnings );

		assertTrue( warnings.isEmpty(), "nested empty finds should not warn: "+ warnings );
	}


	private static String patchWholeRoot( String main, String append ) throws Exception {
		return patchToString( main, append, null );
	}

	private static int countOccurrences( String haystack, String needle ) {
		int count = 0, idx = 0;
		while ( (idx = haystack.indexOf( needle, idx )) != -1 ) {
			count++;
			idx += needle.length();
		}
		return count;
	}
}
