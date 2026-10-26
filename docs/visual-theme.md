# Shorts Creator — Silverlight-inspired visual theme

Reference: user-supplied Silverlight Archives Character Board screenshot (26 Oct 2026).

## Sampled reference palette
| Token | Hex | Usage |
| --- | --- | --- |
| Canvas | `#F5F4EF` | Main page and project surfaces |
| Lavender strip | `#EFE9F5` | Subtle header/navigation sections |
| Card | `#FFFFFF` | Floating tool panels and dialogs |
| Soft lavender chip | `#EDE7F1` | Secondary controls, selected chips |
| Primary purple | `#685B86` | Main action, selected control, active state |

Additional proposed adaptive colors (not sampled directly): `#353238` text, `#D8D5D0` divider, `#292630` dark video/timeline workspace, `#F8F6FB` light-on-purple text.

## Design direction
- Rounded floating cards, generously spaced controls, light backgrounds, subtle borders and gentle shadows.
- Muted purple as the primary accent; avoid saturated blue as the default.
- Light theme on home/project screens; dark preview/timeline panels for contrast with footage and layered clips.
- Touch targets at least 48 dp; clear labels and accessible color contrast.
- Centralize tokens in Compose Material theme and use consistently for overlays, toolbars and update dialogs.
- All cited hex values are starting points from a compressed screenshot and may be refined in real-device testing.
