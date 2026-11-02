# neotime

An unofficial Android client for TimeEdit. Specifically geared towards the University of Twente, also integrating OSIRIS, MazeMap, and people.utwente.nl.

**Why?** Because in the big 2026 (or \<insert current year here\>), TimeEdit still has not bothered to create a mobile app, not even so much as a slight bit of responsiveness on their webapp.

## Features

**Calendar viewer**: view TimeEdit schedules like you're used to on desktop, but in an actual mobile app.

- Integration with `people.utwente.nl`: teacher profile pictures, email addresses & room numbers right from your schedule.
- Integration with OSIRIS: easily add the courses you're currently taking to your calendar, in just a few taps.
- Seperately toggle course components

**Room booking**: book project rooms through TimeEdit, with better user experience.

- Easily see what rooms are available now, and for how long
- Save favorite rooms
- See room availability on the map (MazeMap integration)

**Building maps**: direct MazeMap integration.

- Immediate link through from lecture rooms, booked project rooms, and teacher's office numbers.
- MazeMap data to make the maps clearer: entrances marked, maps rotated.

## Privacy

The app asks users to log in with their UT Microsoft account through a web view within the app. This logs into TimeEdit and the app uses the created authentication cookies to fetch schedules from TimeEdit for the user. It can also create/cancel project room bookings.

It seperately asks consent to use the Microsoft session to also fetch course registrations from OSIRIS. Seperate consent is asked for this because while TimeEdit does not necessarily contain private information, OSIRIS does. This is not required to use the app, and if consent is given, the app does not fetch any other information besides the user's current course registrations for their convenience.

This data is only processed locally on the user's device. There is no data transmitted to other servers. The app also does not have any analytics or telemetry.

The authentication tokens/cookies are stored securely in the device's cryptographic store.