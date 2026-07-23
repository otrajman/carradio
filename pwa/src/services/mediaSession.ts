// Steering-wheel / headset button hack (PROTOCOL §10): register as a media session
// so Play/Pause toggles push-to-talk and Next Track skips + stealth-mutes.
export function registerMediaSession(handlers: {
  onTalkToggle: () => void;
  onSkipMute: () => void;
}) {
  if (!("mediaSession" in navigator)) return () => {};
  const ms = navigator.mediaSession;
  ms.metadata = new MediaMetadata({
    title: "Car Radio — live",
    artist: "Same-traffic voice",
  });
  const pairs: [MediaSessionAction, MediaSessionActionHandler][] = [
    ["play", handlers.onTalkToggle],
    ["pause", handlers.onTalkToggle],
    ["nexttrack", handlers.onSkipMute],
  ];
  for (const [action, fn] of pairs) {
    try {
      ms.setActionHandler(action, fn);
    } catch {
      /* action unsupported */
    }
  }
  return () => {
    for (const [action] of pairs) {
      try {
        ms.setActionHandler(action, null);
      } catch {
        /* noop */
      }
    }
  };
}
