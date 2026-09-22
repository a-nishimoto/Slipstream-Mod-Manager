package net.vhati.modmanager.core;

import java.io.IOException;
import java.io.StringReader;

import org.jdom2.input.SAXBuilder;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


/**
 * Creates SAXBuilders that will not fetch external entities.
 *
 * Mods are untrusted zip archives downloaded from forums, and their XML is
 * parsed automatically during the startup scan. Left unhardened, a mod
 * declaring a DOCTYPE with a SYSTEM entity would make the parser read
 * arbitrary local files, or issue requests to arbitrary URLs, and the result
 * would be shown in the info pane and cached to disk. (XXE.)
 *
 * Two of the callers were previously safe only by accident: patchXMLFile()
 * prepends a synthetic wrapper element, which puts any DOCTYPE after the root
 * element and makes the document malformed, dropping it into SloppyXMLParser,
 * which has no DOCTYPE pattern. Do not rely on that.
 */
public class XMLSecurity {

	/** Resolves every external entity to an empty document. */
	private static final EntityResolver NO_EXTERNAL_ENTITIES = new EntityResolver() {
		@Override
		public InputSource resolveEntity( String publicId, String systemId ) throws SAXException, IOException {
			return new InputSource( new StringReader( "" ) );
		}
	};


	/**
	 * Returns a SAXBuilder which neither expands nor fetches external entities.
	 */
	public static SAXBuilder newSecureSAXBuilder() {
		SAXBuilder builder = new SAXBuilder();

		builder.setExpandEntities( false );
		builder.setEntityResolver( NO_EXTERNAL_ENTITIES );

		return builder;
	}


	private XMLSecurity() {
	}
}
