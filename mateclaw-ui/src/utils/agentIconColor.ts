/**
 * Per-role accent color for digital-employee icons.
 *
 * The pixelarticons SVGs render with `fill="currentColor"`, so wrapping
 * the icon in an element with a `color: ...` style tints the glyph
 * without touching the SVG itself. We only use this in agent contexts
 * (cards, picker, chat header) so skills / tools keep their default
 * neutral color.
 *
 * The palette follows NewsClaw's editorial workspace system: teal for
 * operational work, blue for evidence and analysis, coral for attention,
 * and a restrained amber for assistant identity. It keeps role icons
 * distinguishable without turning the interface into a rainbow dashboard.
 */

const BRAND_FALLBACK = 'var(--mc-primary)'

/** icon name (without `pi:` prefix) → CSS color */
const ICON_COLOR_MAP: Record<string, string> = {
  // Engineering / inspection — coral marks an item that needs attention.
  'bug': 'hsl(4, 78%, 57%)',
  'search': 'hsl(4, 78%, 57%)',

  // Research / writing — teal and indigo distinguish source work from prose.
  'book-open': 'hsl(173, 65%, 32%)',
  'notes': 'hsl(236, 55%, 56%)',
  'article': 'hsl(173, 65%, 32%)',

  // Data / analytics — evidence blue.
  'chart-bar-big': 'hsl(217, 76%, 52%)',
  'chart': 'hsl(217, 76%, 52%)',
  'analytics': 'hsl(217, 76%, 52%)',

  // Customer / support — coral keeps conversation work visible.
  'headphone': 'hsl(4, 78%, 57%)',
  'message': 'hsl(4, 78%, 57%)',
  'message-text': 'hsl(4, 78%, 57%)',

  // General / friendly assistants — warm amber
  'robot-face-happy': 'hsl(38, 72%, 50%)',
  'robot-face': 'hsl(38, 72%, 50%)',
  'robot': 'hsl(38, 72%, 50%)',

  // System / infrastructure — slate blue.
  'cpu': 'hsl(207, 34%, 43%)',
  'cloud': 'hsl(207, 34%, 43%)',

  // Planning / task — indigo
  'clipboard-note': 'hsl(232, 38%, 52%)',
  'clipboard': 'hsl(232, 38%, 52%)',
  'list-box': 'hsl(232, 38%, 52%)',
  'checkbox-on': 'hsl(232, 38%, 52%)',
}

/**
 * Return the accent color for a stored icon string. Returns the brand
 * primary as a CSS variable for emoji / URL / unknown icons so callers
 * can apply the color unconditionally.
 */
export function agentIconColor(iconValue: string | null | undefined): string {
  if (!iconValue) return BRAND_FALLBACK
  const v = iconValue.trim()
  if (!v.startsWith('pi:')) return BRAND_FALLBACK
  const name = v.slice(3)
  return ICON_COLOR_MAP[name] || BRAND_FALLBACK
}
