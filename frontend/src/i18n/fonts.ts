import { Asset } from 'expo-asset';
import { Platform } from 'react-native';

/**
 * Inter and Plus Jakarta Sans have no Georgian glyphs (checked: U+10D0-10FF is absent from both files), so Georgian
 * text needs a bundled fallback. Noto Sans Georgian is SIL OFL 1.1 (assets/fonts/NotoSansGeorgian-OFL.txt).
 */
export const GEORGIAN_FONT_FAMILY = 'Noto Sans Georgian';
export const georgianFontSource: number = require('../../assets/fonts/NotoSansGeorgian-Variable.ttf');

/** Georgian, Georgian Extended (Mtavruli), Georgian Supplement. */
const GEORGIAN_UNICODE_RANGE = 'U+10A0-10FF, U+1C90-1CBF, U+2D00-2D2F';

/** The families the interface sets by name; screens write `fontFamily: 'Plus Jakarta Sans'` without a fallback list. */
const FAMILIES_WITHOUT_GEORGIAN = ['Inter', 'Plus Jakarta Sans'];

let installed = false;

/**
 * Web: adds a second face to each interface family that is used only for Georgian code points (unicode-range), so a
 * heading set in 'Plus Jakarta Sans' renders Georgian with Noto Sans Georgian and everything else unchanged. Call after
 * the interface fonts have loaded: for one code point the browser prefers the face defined last, and the face that
 * expo-font generates covers the whole range.
 *
 * Native needs no equivalent: iOS and Android resolve a glyph the font lacks through the operating system's own
 * fallback chain, which includes a Georgian font. The family is still registered (see the root layout) so a text can
 * name it explicitly.
 */
export function installGeorgianFallback() {
  if (installed || Platform.OS !== 'web' || typeof document === 'undefined' || typeof FontFace === 'undefined') {
    return;
  }
  installed = true;
  const uri = Asset.fromModule(georgianFontSource).uri;
  for (const family of FAMILIES_WITHOUT_GEORGIAN) {
    const face = new FontFace(family, `url(${JSON.stringify(uri)})`, {
      unicodeRange: GEORGIAN_UNICODE_RANGE,
      weight: '100 900',
      display: 'swap',
    });
    document.fonts.add(face);
  }
}
