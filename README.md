# Dayloop

A personal Android app that joins a task inbox, a habit tracker, a time tracker and a
SuperMemo-style day plan into one loop: **capture → plan → do → review**.

Everything is stored on the phone. There is no account and no server.

## What it does

**Tasks.** One line of text is enough: `buy bread at 9am on 10/12`. The date, time, length,
group and repetition are picked out as you type and highlighted. Tasks without a date wait
in the Inbox; the rest sort into Overdue, Today, Tomorrow, Upcoming and Someday. A task with
a time gets a notification at that time, with Done, Start timer and Snooze buttons.

| You type | It understands |
|---|---|
| `call bank tomorrow 14:30` | tomorrow, 14:30 |
| `report friday for 90m #work` | next Friday, 90 minutes, group Work |
| `dentist 3 dec at 9am` · `in 2 weeks` · `2027-02-01` | dates |
| `vitamins every day 8am` · `gym every mon, wed` · `rent monthly` | repeating tasks |
| `standup 0830` · `dinner at 2000` | military time |
| `pay rent tomorrow p1` | priority 1 (highest) to 4 |
| `report #work` · `report @work` | group; typing `#` or `@` offers the existing ones |
| `comprar pão amanhã às 9h` | common Portuguese words work too |

A bare four-digit number is read as a time unless it looks like a nearby year (`2027`), and
priorities colour the task's checkbox and sort it first within its day.

Whether `10/12` means 10 December or October 12 follows the phone's settings and can be
changed in Settings.

**Habits.** Each habit has the weekdays it applies to, an optional reminder time and an
optional timed goal (a habit with a 20-minute goal ticks itself once 20 minutes are tracked).
A habit can be done in several **sessions per day**: "Study, 2h, 2 sessions" goes on the plan
as two one-hour activities, and the habit is ticked once both are done.
The Habits tab shows a GitHub-style heatmap of all habits together, and each habit has its
own.

**Timer.** A bar above the tabs starts a timer for something on today's plan, a habit, a
task, a recent entry or free text. The running timer also shows as a notification with a
Stop button. Entries can be renamed or put in a group afterwards on the Review tab.

**Groups.** Colour-coded areas (Work, Health, …) shared by tasks, habits, plan activities
and time entries. Typing `#name` creates a group if it does not exist.

**Plan.** The day is a list of activities, each with a desired length. As in
[SuperMemo Plan](https://www.super-memory.com/help/plan.htm):

- an activity can have a **Fixed** start time and/or a **locked** length (the lock icon on
  each row; SuperMemo calls this Rigid). Something typed with a start time is locked from
  the start, like an appointment;
- activities between two fixed points are shortened proportionally when they do not fit;
  locked ones keep their length and the others adjust around them;
- **Begin** pins an activity to the current time, starts the timer for it, and lays out the
  rest of the day again — being late does not break the plan;
- finished activities keep their real times, and the Review tab compares planned and actual
  lengths, so recurring activities show how long they really take.

Two views of the same plan, switched with **Blocks / List** at the top: the list, and a
time-block day view like a calendar's. In the block view, hold and drag an activity to pin it
to another time, drag its bottom edge to change its length, and tap an empty spot to add one
there.

Tap an activity to edit it. There you can also **split** it into 2–4 sessions that all count
towards the same task or habit, and correct **when it really started and ended** — for when
you forgot to switch: set the previous one's end to an hour ago and the current one's start
to 30 minutes ago. The running timer's start can be corrected by tapping it in the timer bar,
and any time entry on the Review tab.

Activities are typed in (`Deep work 90m`, `Lunch at 12:30 for 45m`) or pulled in from the
task lists, from the day's habits, from the phone's calendar, or from a saved day template.

**Also:** light, dark or follow-the-system theme; a home-screen widget and a launcher shortcut for adding a task, "Share" from other
apps into the inbox, morning "plan your day" and evening "review your day" prompts, and JSON
export, restore and an automatic daily backup to a folder you choose.

## Installing

Every push builds an APK. On GitHub open **Actions → Build →** the latest run and download
`dayloop-debug-apk`, then open the file on the phone. All builds are signed with the same
key (`app/dayloop-debug.keystore`), so a new build installs over the old one and keeps the
data.

To build locally: `./gradlew assembleDebug` (JDK 17 or newer and the Android SDK, API 35).

## Code map

| Path | Contents |
|---|---|
| `app/src/main/java/com/thor1or/dayloop/logic` | `PlanEngine` (the scheduling rules), `NlParser` (free text to date, time, length), `Recurrence` |
| `…/data` | Room entities and DAO, `Repository` (all changes go through it), settings, backup |
| `…/notify` | Alarms, notifications and their buttons |
| `…/ui` | One file per screen, plus the timer bar and shared pieces |
| `app/src/test` | Unit tests for the engine and parser, `RepositoryTest` for the rules on a real database (including the upgrade from the first version), and `SmokeTest`, which runs the real screens on the JVM and writes screenshots to `app/build/screens` |

Run the tests with `./gradlew testDebugUnitTest`.
