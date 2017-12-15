#!/bin/bash

docker run --rm -v "$PWD":/home/gradle/project -w /home/gradle/project -p 8080:8080 gradle gradle run

