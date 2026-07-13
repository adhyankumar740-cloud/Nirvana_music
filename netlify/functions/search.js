const { json, withTimeout, parseIso8601DurationSeconds, withTrailingSlash } = require("./_util");

// All three of these live ONLY as Netlify environment variables (Site
// configuration > Environment variables) - never committed, never sent to
// the app. The app calls this function with no key of any kind.
const RELAY_BASE_URL = process.env.RELAY_BASE_URL; // your real relay backend, e.g. https://your-relay-host.example.com/
const RELAY_API_KEY = process.env.RELAY_API_KEY || "";
const YOUTUBE_API_KEY = process.env.YOUTUBE_API_KEY || "";

exports.handler = async (event) => {
  if (event.httpMethod === "OPTIONS") return json(200, {});
  if (event.httpMethod !== "GET") return json(405, { error: "Method not allowed." });

  const query = (event.queryStringParameters?.query || "").trim();
  const limit = Number(event.queryStringParameters?.limit) || 20;

  if (!query) {
    return json(400, { error: "Missing required 'query' parameter." });
  }

  // PRIMARY: the real relay backend (adds X-Relay-Key itself, server-side).
  if (RELAY_BASE_URL) {
    try {
      return json(200, await searchViaRelay(query, limit));
    } catch (err) {
      console.error("[search] relay failed, falling back to YouTube:", err.message);
    }
  }

  // FALLBACK: YouTube Data API v3, reshaped into the same { query, results }
  // contract as the relay so the app doesn't need to know which path served it.
  if (!YOUTUBE_API_KEY) {
    return json(502, {
      error: "Search unavailable: the relay backend failed and no YouTube fallback is configured on the server.",
    });
  }

  try {
    return json(200, await searchViaYouTube(query, limit));
  } catch (err) {
    console.error("[search] YouTube fallback failed:", err.message);
    return json(502, { error: "Search failed on both the relay and the YouTube fallback." });
  }
};

async function searchViaRelay(query, limit) {
  const base = withTrailingSlash(RELAY_BASE_URL);
  const url = `${base}search?query=${encodeURIComponent(query)}&limit=${limit}`;
  const { signal, clear } = withTimeout(9000);
  try {
    const headers = RELAY_API_KEY ? { "X-Relay-Key": RELAY_API_KEY } : {};
    const res = await fetch(url, { headers, signal });
    if (!res.ok) throw new Error(`Relay search returned HTTP ${res.status}`);
    const data = await res.json();
    return { query: data.query ?? query, results: Array.isArray(data.results) ? data.results : [] };
  } finally {
    clear();
  }
}

async function searchViaYouTube(query, limit) {
  const { signal, clear } = withTimeout(9000);
  try {
    const searchUrl =
      "https://www.googleapis.com/youtube/v3/search" +
      `?part=snippet&type=video&videoCategoryId=10&videoEmbeddable=true` +
      `&maxResults=${limit}&q=${encodeURIComponent(query)}&key=${YOUTUBE_API_KEY}`;
    const searchRes = await fetch(searchUrl, { signal });
    if (!searchRes.ok) throw new Error(`YouTube search.list returned HTTP ${searchRes.status}`);
    const searchData = await searchRes.json();
    const videoIds = (searchData.items || []).map((it) => it.id?.videoId).filter(Boolean);
    if (videoIds.length === 0) return { query, results: [] };

    const detailsUrl =
      "https://www.googleapis.com/youtube/v3/videos" +
      `?part=snippet,contentDetails&id=${videoIds.join(",")}&key=${YOUTUBE_API_KEY}`;
    const detailsRes = await fetch(detailsUrl, { signal });
    if (!detailsRes.ok) throw new Error(`YouTube videos.list returned HTTP ${detailsRes.status}`);
    const detailsData = await detailsRes.json();

    const results = (detailsData.items || []).map((item) => ({
      video_id: item.id,
      title: item.snippet?.title || "Unknown Title",
      artist: item.snippet?.channelTitle || "Unknown Artist",
      thumbnail:
        item.snippet?.thumbnails?.high?.url ||
        item.snippet?.thumbnails?.medium?.url ||
        item.snippet?.thumbnails?.default?.url ||
        "",
      duration_sec: parseIso8601DurationSeconds(item.contentDetails?.duration),
    }));
    return { query, results };
  } finally {
    clear();
  }
}
