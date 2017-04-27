# NyaaUtilsLangChecker
Check lang files at compile time. Designed to work with plugins based on NyaaUtils. 

[![Build Status](https://travis-ci.org/Librazy/NyaaUtilsLangChecker.svg?branch=master)](https://travis-ci.org/Librazy/NyaaUtilsLangChecker)

# Usage
Modify your build.gradle like below.

```gradle
plugins {
    id 'java'
    id 'org.inferred.processors' version '1.2.11'
}

repositories {
    maven {
        name 'nu-langchecker'
        url 'https://raw.githubusercontent.com/Librazy/NyaaUtilsLangChecker/maven-repo'
    }
}

dependencies {
    compile 'org.librazy:NyaaUtilsLangChecker:1.0-SNAPSHOT'
    processor 'org.librazy:NyaaUtilsLangChecker:1.0-SNAPSHOT'
    processor 'org.spigotmc:spigot-api:1.11.2-R0.1-SNAPSHOT'
}

compileJava {
    options.compilerArgs += ["-Xplugin:NyaaUtilsLangAnnotationProcessor"]
}
```

Annotate your function parameter that recieve a lang key and string constants contains a lang key with @LangKey
```java
    @LangKey private static final String BED_NOT_SET_YET = "user.teleport.bed_not_set_yet";

    public Message appendFormat(Internationalization i18n, @LangKey String template, Object... obj) {
        return append(i18n.get(template, obj));
    }
```

If it find a key that didn't exist in lang.yml, it'll complain at compile time!
e.g.
```bash
Note: Lang resources path:/home/travis/build/Librazy/nyaautils/src/main/resources/lang
Note: /home/travis/build/Librazy/nyaautils/src/main/resources/lang/en_US.yml
/home/travis/build/Librazy/nyaautils/src/main/java/cat/nyaa/nyaautils/commandwarpper/Teleport.java:32: warning: Key user.teleport.bednot_set_yet not found in lang en_US.yml
    @LangKey private static final String BED_NOT_SET_YET = "user.teleport.bednot_set_yet";
                                         ^
/home/travis/build/Librazy/nyaautils/src/main/java/cat/nyaa/nyaautils/CommandHandler.java:78: warning: Key user.launch.uage not found in lang en_US.yml
            sender.sendMessage(I18n.format("user.launch.uage"));
                                           ^
```
