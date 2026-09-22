package net.vhati.modmanager.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ComparableVersionTest {

	/**
	 * equals() used two guards that both fall through when neither version has a
	 * suffix -- the common case -- and then dereferenced null. compareTo() has
	 * always used the correct three-way form, so sorting worked while comparing
	 * threw, which is why this survived: the app keeps versions in a TreeMap.
	 */
	@Test
	public void plainVersionsCompareWithoutThrowing() {
		assertEquals( new ComparableVersion( "1.2" ), new ComparableVersion( "1.2" ) );
		assertNotEquals( new ComparableVersion( "1.2" ), new ComparableVersion( "1.3" ) );
	}

	@Test
	public void suffixesAndCommentsStillDistinguish() {
		assertNotEquals( new ComparableVersion( "1.2" ), new ComparableVersion( "1.2-beta" ) );
		assertNotEquals( new ComparableVersion( "1.2-beta" ), new ComparableVersion( "1.2" ) );
		assertEquals( new ComparableVersion( "1.2-beta" ), new ComparableVersion( "1.2-beta" ) );
	}

	@Test
	public void equalVersionsAgreeWithCompareToAndHashCode() {
		ComparableVersion a = new ComparableVersion( "1.9.1" );
		ComparableVersion b = new ComparableVersion( "1.9.1" );

		assertEquals( 0, a.compareTo( b ), "compareTo disagrees with equals" );
		assertEquals( a.hashCode(), b.hashCode(), "equal versions must share a hash" );
	}

	@Test
	public void orderingIsUnchanged() {
		assertTrue( new ComparableVersion( "1.2" ).compareTo( new ComparableVersion( "1.10" ) ) < 0 );
		assertTrue( new ComparableVersion( "2.0" ).compareTo( new ComparableVersion( "1.9.1" ) ) > 0 );
	}
}
