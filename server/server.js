import http from "node:http";

const port = Number(process.env.PORT || 3000);
let postCount = 0;

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url.startsWith("/boattracker/startup")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      events: [
        {
          name: "3. Keso Pal Fenyves Kupa",
          start: "2026-08-30",
          configUrl: `http://10.0.2.2:${port}/api/pozicio/init?hajo=pentike-teszt&nevezesId=test`,
        },
        {
          name: "Maria Kupa",
          start: "2026-05-24",
          configUrl: `http://10.0.2.2:${port}/api/pozicio/init?hajo=pentike-teszt&nevezesId=test`,
        },
        {
          name: "Gyenes Kupa",
          start: "2026-06-10",
          configUrl: `http://10.0.2.2:${port}/api/pozicio/init?hajo=pentike-teszt&nevezesId=test`,
        },
      ],
    }));
    return;
  }

  if (req.method === "GET" && req.url.startsWith("/api/pozicio/init")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      logo: `https://fenyvesvit.hu/sites/fenyvesvit.hu/themes/adt_higherground/logo.png5`,
      event: "3. Késő Pál Fenyves Kupa",
      motto: "A szél legyen velünk!",
//      url: `https://fenyveskupa.hu/api/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/hajok`,
      url: `http://10.0.2.2:${port}/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/hajok`,
    }));
    return;
  }

  if (req.method === "GET") {
    const host = req.headers.host || `10.0.2.2:${port}`;
    //const initUrl = `https://fenyveskupa.hu/api/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/init?hajo=pentike-teszt&nevezesId=test`;
    const initUrl = `http://10.0.2.2:${port}/api/pozicio/init?hajo=pentike-teszt&nevezesId=test`;
    const deepLink = `boattracker:${initUrl}`;

    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Boat Tracker Test</title>
  <style>
    body { font-family: sans-serif; margin: 32px; line-height: 1.4; }
    a { display: inline-block; padding: 14px 18px; background: #0b6e4f; color: white; text-decoration: none; border-radius: 6px; }
    code { display: block; margin-top: 16px; word-break: break-all; }
  </style>
</head>
<body>
  <h1>Boat Tracker Test</h1>
  <p>Open this page in Chrome on the emulator, then tap the link.</p>
  <a href="${deepLink}">Start tracker</a>
  <code>${deepLink}</code>
  <p>Page served from ${host}. The Android emulator reaches the server at 10.0.2.2.</p>
</body>
</html>`);
    return;
  }

  const chunks = [];

  req.on("data", (chunk) => chunks.push(chunk));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString("utf8");
    let coordinates = null;

    try {
      coordinates = body ? JSON.parse(body) : null;
    } catch (error) {
      console.warn("Invalid JSON body:", body);
    }

    if (req.method === "POST") {
      postCount += 1;
      console.log(new Date().toISOString(), req.url, coordinates || body);
    }

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      f: Number(process.env.FREQUENCY || 30),
      msg: postCount > 0 && postCount % 2 === 0
        ? process.env.MESSAGE || `Test message after request ${postCount}`
        : "",
    }));
  });
});

server.listen(port, () => {
  console.log(`Boat tracker test server listening on http://0.0.0.0:${port}`);
});
