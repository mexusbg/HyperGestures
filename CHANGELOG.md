# Changelog

## 0.4.1-beta

Install in KernelSU, then reboot fully.

- **Works after HyperOS updates:** HyperGestures reads HyperOS's split-screen code from the phone
  itself instead of being tied to one HyperOS version. On OS4.0.0.13 this fixes the split card
  when you swipe up from a split pair, and splits started from recents can be resized again. If a
  later update ever changes something, only that feature falls back, and the status card shows a
  "Firmware compatibility" row saying which. The "New HyperOS version" notification is gone.
- **Hold buttons:** they sit right next to the card, and other cards slide out of their way. In the
  stack the first card's buttons are on the right and every other card's on the left; in the
  horizontal and grid layouts they open on the side where no other card is.
- **Notification icons:** apps that post several different icons show each one correctly, a
  work-profile app keeps its badge, icons follow app updates, and they use less memory.
- **Recents:** locking a split card from its menu locks both apps; header taps and the hold menu
  stay consistent after you change the header setting; the cleared toast always goes away.
- **Back:** no vibration or arrow animation when a back is not sent; the back area follows a
  Display size change.
- **Module:** "Restore" right after boot is no longer undone by the module's own setup; the
  battery setting is put back reliably; fewer checks at boot.
- **Security:** the screenshot share provider only shares recents screenshots; an unused
  permission and an unused exported component are gone.
- **Settings:** tapping a module notice opens settings, and the page starts faster.

## 0.4.0-beta

The first beta. Install in KernelSU, then reboot fully.

- **Settings page redesign** in HyperOS's About-phone colours, with a large title that scrolls
  away.
- **Status card** at the top: whether HyperGestures is running, plus module, mount, recents
  provider, gestures and back, LSPosed hooks, and whether a reboot is pending.
- **Recents layout picker:** three live tiles (Vertical, Horizontal, Stack); tap one to pick it.
- **Circle to Search** is now a switch. It is off and greyed out while the System launcher's
  "Hide navigation line" is on, and your earlier choice comes back when you turn that off.
- **MIUI vibrations** for Clear all and for locking or unlocking a card.
- **Lock badge** fades in and out when a card is locked or unlocked.
- **Memory line** is turned on and off with the Show memory switch in settings (the 5-second
  hold on the trash button is gone). It updates after you swipe a card away.
- **TalkBack** no longer reads stack cards hidden behind the deck.
- **Fixes:** the home screen no longer shows over recents when you open recents from a
  see-through app such as Viber's chat; Facebook no longer disappears from recents after going
  home from a video; the firmware notice shows once per HyperOS version.
- **Updates through KernelSU:** the module now checks for new versions itself.
- **License** is now PolyForm Noncommercial 1.0.0. The module zip includes LICENSE and NOTICE.
