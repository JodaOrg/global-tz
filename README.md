# Global Time Zone Database (global-tz)

## Data files

The Global Time Zone Database files can be found on the [global-tz](https://github.com/JodaOrg/global-tz/tree/global-tz) branch.
This branch contains the tool that generates the database.

## Status

The Global Time Zone Database was last generated at 2024-11-14T13:36:05.425312666Z.
It is up to date with commit cfacf5ea700c888b6b58a8562524304013ee9057 from the IANA Time Zone database.


## Rationale

This [repository](https://github.com/JodaOrg/global-tz) contains the Global Time Zone Database.
It is derived from data in the [IANA Time Zone Database](https://github.com/eggert/tz).

This database contains the history of how local time has changed around the world.
For many years this information could be reliably obtained from the IANA repository.
In recent years however, the data available by default from the IANA repository has been reduced.
This repository primarily exists to reinstate the data that has been effectively removed.

There are two basic models of time zones in the world.

* Multiple time zones per country - Some countries have many different time zones.
  Ordinary members of the public are expected to be aware of their time zone.
* Country based time zones - Some countries have one time zone for the whole country.
  Ordinary members of the public refer to their time zone relative to their country.

Until recently, the IANA database followed an approach that handled both of these models.
However, a change in approach means that the IANA database now favours the first model.
In practical terms, the IANA database intends to capture the minimal amount of data necessary to describe
changes to local time since 1970, *irrespective of country boundaries*.
The global-tz project exists to reinstate the data that has been lost, supporting both models of time zone.
In practical terms, global-tz takes into account country boundaries, whereas the IANA database no longer does.

As an example of the problem with the IANA database approach, the identifier 'Europe/Reykjavik' commonly
used in Iceland is to be treated as an alias for 'Africa/Abidjan'.
This is because they have the same Time Zone rules since 1970 and Abidjan is a larger city than Reykjavik.
The result is that IANA database data for Reykjavik prior to 1970 is incorrect.
By contrast, global-tz uses the previous approach that supports both models of Time Zones,
ensuring that identifiers only alias across an ISO 3166-1 boundary where no other data is available.


## ISO-3166-1

The global-tz project does not define what is, or is not, a country.
Instead this project uses the broadly accepted neutral standard maintained by the ISO 3166 Maintenance Agency
based on information from the United Nations Headquarters.


## Reinstating the data

Using the IANA data as a base, global-tz reinstates the data removed from the IANA database.
The approach taken supports full time zone data according to these basic rules:

* An identifier for each region of the world where clocks have differed since 1970-01-01.
* At least one identifier for each inhabited [ISO 3166-1 code](https://en.wikipedia.org/wiki/ISO_3166-1).
* For these identifiers the data returned shall be the best available, including pre-1970 history.

In addition to the supported set of identifiers derived from the rules above, other identifiers from the IANA database are made available.
These do not necessarily follow the "best available" rule.

Wherever possible, data will be derived from the IANA database.
Original data in global-tz will be kept to a minimum.
As such, most issues should be raised with the IANA data set, not here.
Most PRs/Issues raised here will be closed.


## Tooling

The project uses a tool to automatically derive the data from the IANA database.

The tool is run as a schedule GitHub Action, using a combination of the Action yml file and code.
This code is written as a script in Java suitable for running JDK 11 or later (compilation is not required).

When run, the tool performs the following:

* Loads `actions.txt`
* Processes each action sequentially
* Merges the content from `iana` and the generated changes into `global`
* Creates new git commits

The output of the tool is found on the [global-tz](https://github.com/JodaOrg/global-tz/tree/global-tz) branch.
The code on that branch is identical to that of the IANA Time Zone Database.
The data on that branch is adjusted as per the rules of this project.


## Contributing

Errors in the timezone data should be taken up at the [IANA Time Zone Database](https://github.com/eggert/tz).

Issues should be raised at this project only where the process of reinstating data from IANA is incorrect.
