package lcms4j.xyz.controls;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link IccProfile} class
 */
public class IccProfileTest {

	/**
	 * Test method for {@link IccProfile#getProfileInfo()}.
	 */
	@Test
	public void testGetProfileInfo() {
		try {
			IccProfile profile = new IccProfile(IccProfile.PROFILE_ADOBERGB);
			String profileInfo = profile.getProfileInfo();
			assertEquals("Adobe RGB (1998)", profileInfo);
		} catch (LCMS4JException e) {
			fail("Exception loading AdobeRGB Icc Profile: "+e.getMessage());
			return;
		}
	}
}
