# qiandao

Minecraft 1.21.11 / fabric

Build with `./gradlew build` (Windows: `.\gradlew.bat build`).

This project was created with ModMind from the official fabric template structure.

## Usage

Use `/qiandao`, `/qiandao open`, or `/checkin` to open the five-row check-in menu.
The first four rows show the days in the current month. Click only today's slot to
check in; the server validates the slot index and records the result in persistent
world data. A checked day is shown as an enchanted book, and an unchecked day as a
regular book. Hover the player head in the center of the bottom row to see today's
ordinal, total check-ins, and current streak.

The current date is determined by the server JVM's system time zone.
