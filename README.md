# Boat Tracker

Android Kotlin app that starts from a `boattracker:` URL, tracks GPS position, and posts coordinates to the startup URL at the frequency returned by the server.

Example deep link:

```text
boattracker:https://fenyveskupa.hu/api/pozicio/init?hajo=pentike-teszt&nevezesId=68b1f653221a01cf65c552b3
```

The app first sends a `GET` to the deep-link URL, or to `https://fenyveskupa.hu/api/pozicio/init` when launched directly. The init response provides the coordinate POST URL.

Then the app sends:

```json
{
  "n": "pentike-teszt",
  "id": "68b1f653221a01cf65c552b3",
  "la": 34.234234,
  "lo": 34.232343
}
```

The server responds:

```json
{"f":30,"msg":"Message to be displayed on screen"}
```

## Build

```bash
./gradlew assembleDebug
```

## Test Server

```bash
node server/server.js
```

Optional settings:

```bash
PORT=3000 FREQUENCY=10 MESSAGE="Race office message" node server/server.js
```

For emulator testing against the host machine, use a link such as:

```text
boattracker:http://10.0.2.2:3000/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/hajok?hajo=pentike-teszt&nevezesId=test
boattracker:http://10.0.2.2:3000/api/pozicio/init?hajo=pentike-teszt&nevezesId=test
```
