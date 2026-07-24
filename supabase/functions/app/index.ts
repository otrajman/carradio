// The Supabase gateway forces text/plain on HTML responses, so the demo is hosted
// on GitHub Pages instead. This URL stays as a stable alias.
Deno.serve(() =>
  new Response(null, {
    status: 302,
    headers: { Location: "https://otrajman.github.io/carradio/" },
  })
);
