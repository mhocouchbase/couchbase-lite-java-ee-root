#!/usr/bin/perl -w
use strict;

while (<>) {
    chomp;
    my $file = $_;
    next unless (-e $file);
    next unless open (UPDATE, '<', $file);
    next unless open (TMP, '>', ".copyright");
    fix_copyright();
    close UPDATE;
    close TMP;
    system "mv .copyright $file";
}

sub fix_copyright {
    my $lines = 0;
    while (<UPDATE>) {
       if (($lines++ < 10) && (/\/\/\s+Copyright\s+\(c\)\s+(\d{4})/) && ($1 ne "2020")) {
           s/(\/\/\s+Copyright\s+\(c\)\s+)(\d{4})/${1}2020, $2/;
       }
       print TMP $_;
    }
}

