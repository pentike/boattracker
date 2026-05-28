Create a new Android Kotlin app that tracks GPS position
- use gradle

# Startup
app can be started with a url intent sent to the user via email
boattracker:https://fenyveskupa.hu/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/hajok


boattracker:http://10.0.2.2:3000/verseny/harmadik-keso-pal-fenyves-kupa/terkep/kovetes?hajo=
pentike-teszt&nevezesId=test

If the app is started directly and not through a deeplink, it should request configuration from
GET https://fenyveskupa.hu/boattracker/startup
response {
	"events": [
		{
			name: "3. Késő Pál Fenyves Kupa",
			start: "2026-08-30",
			configUrl: "https://fenyveskupa.hu/api/pozicio/init"
		},
		{
			name: "Mária Kupa",
			start: "2026-05-24"
			configUrl: "https://fenyveskupa.hu/api/pozicio/init"
		},
		{
			name: "Gyenes Kupa",
			start: "2026-06-10"
			configUrl: "https://fenyveskupa.hu/api/pozicio/init"
		},
	]
}

and show the user the list of events to choose from.
If there is any problem with fetching the response, an error message page is displayed as follows.
app icon
"Boat Tracker" title
"GPS Boat Tracker application." subtitle 
"Unable to fetch configuration from the server. This might be a network issue or the server may be down at the moment.

Please try again later or contact your event organizer!"



# Main screen

it has a main screen displaying
- occasional message box
- "Your data is sent to" https://fenyveskupa.hu"
  - add a blinking green dot indicating that sending is enabled
- the name of the ship
- the current gps coordinates
- the time of the last broadcast without the date
- a switch with witch you may turn off broadcasting your position on by default
  - if turned off a warning notice saying "Your position is not visible to the organizers!"
- the logo of the competition in the background
- Racing rules list
- message list

# Communication with the backend server

Communication starts with sending a GET request to the url given at starup in the deeplink.
If the app is started directly it must use the default configuration endpoint provided in the Startup section and use the configUrl for the chosen event, i.e.:
https://fenyveskupa.hu/api/pozicio/init
response {
	"logo":"https://fenyveskupa.hu/verseny/harmadik-keso-pal-fenyves-kupa/logo.png",
	"event":"3. Késő Pál Fenyves Kupa"
	"motto":"A szél legyen velünk"
	"url":"https://fenyveskupa.hu/verseny/harmadik-keso-pal-fenyves-kupa/pozicio/hajok"
}

Sending of the gps coordinates is started by POSTing to the url got from the initial response.
request
{
"n":"pentike-teszt"
"id":"68b1f653221a01cf65c552b3"
"la":34.234234
"lo":34.232343
}
response
{
	"f":30, //frequency in seconds
	"msg": "Message to be displayed on screen"
}
If the request fails, display a "Communication error." message in the top message area.
The first call should include coordinates.
The response contains the frequency of the broadcast.
The app keeps broatcasting at the given frequency even if it is sent to the background. It stops broadcasting if the user quits the app which means the user forc-closes the app, swipes it out or taps the broadcast swith on the screen.
Background broadcasting should be implemented in the most reliable way.

The ship name should be displayed as is.

If the app receives a message in the response, it displays it at the top of the screen in a separate section. The default information should be still visible on the page, scrolling is allowed. The message keeps being displayed until the user dismisses it with an x with a circle background.
The message is also displayed in the message list. New messages are at the top of the list.
If a new message arrives, play a message tone (ship horn) and activate the buzzer.
Message list should be cleared if user kills the app.


# Logo

Default competition logo is built into the app but it may gather the logo from the following url:
https://fenyveskupa.hu/verseny/harmadik-keso-pal-fenyves-kupa/logo
only try to load the logo at startup

Put a description text at the bottom of the screen as follows:
"""
Your position is sent to the organizers to track your boat. It is only recorded during the time of the event.
"""
Optimize screen for phones
Use the screen as efficient as possible. Text should be large and readable. Slightly round the edges.

create a simple nodejs server to test the app with that prints out the received coordinates
The server responds with a message every 5th request.

use platform best practice for string translation

# Translation

create hungarian translation and add a language selector before the server line
the language selector only displays the current language and if the user taps on it he can
  select from the available languages


# Racing rules

add simple list for sailboat racing rules having an image and a description at the bottom of the main screen before the message list. The rulesa are:
  1. port track must keep clear of starboard track
  2. windward boat must keep clear of leeward boat
  3. board overtaking must keep clear
  4. rounding mark outside boats must give enough room to the inside boat
  see https://dockstahavet.se/blog/basic-rules-of-sailing-racing for samples

if the user taps on a rule, open a new screen with the image for that specific rule. the
  screen can be closed with standard back movement or an x in the corner
