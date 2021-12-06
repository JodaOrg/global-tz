# Global Time Zone Database (global-tz)

## Introduction

This [repository](https://github.com/JodaOrg/global-tz) contains the Global Time Zone Database.
It is derived from data in the [IANA Time Zone Database](https://github.com/eggert/tz).

See the main [README]() for more background information.


## Generating global-tz

The main branch of global-tz is derived from the [IANA Time Zone Database](https://github.com/eggert/tz).
This "generate" branch contains the code necessary to make the changes.
This code is written as a script in Java suitable for running JDK 11 or later (compilation is not required).
The script directly executes `git`, so it must be avaialble on the command line.

When run, the tool performs the following:

* Clones https://github.com/eggert/tz into the subdirectory `iana`
* Clones https://github.com/JodaOrg/global-tz into the subdirectory `global`
* Loads `actions.txt`
* Processes each action sequentially
* Merges the content from `iana` and the generated changes into `global`
* Creates a new git commit

Invoke the tool using `java GenerateGlobalTz.java`.


## Contributing

Errors in the timezone data should be taken up at the [IANA Time Zone Database](https://github.com/eggert/tz).

Issues should be raised at this project only where the process of reinstaing data from IANA is incorrect.
