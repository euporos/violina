#!/bin/bash



for image in `echo $@` ; do
    echo → 1024 $image
    test=`basename "$image"`
    filename=`echo $test | rev | cut --delimiter="." --fields 2- | rev`
    extension=`echo $test | rev | cut --delimiter="." --field 1 | rev`
    echo $filename.$extension
    convert $image -resize 1920x1920\> -quality 70 public/compiled/from_reservoir/"$filename-1920.$extension"
    convert $image -resize 1024x1024\> -quality 70 public/compiled/from_reservoir/"$filename-1024.$extension"
    convert $image -resize 768x768\> -quality 70 public/compiled/from_reservoir/"$filename-768.$extension"
    convert $image -resize 512x512\> -quality 70 public/compiled/from_reservoir/"$filename-512.$extension"
done
