#!/usr/bin/perl -w
use strict;

while (<>) {
    chomp;
    my $file = $_;
    next unless (-e $file);
    next unless open (UPDATE, '<', $file);
    next unless open (TMP, '>', ".license");
    fix_license();
    close UPDATE;
    close TMP;
    system "mv .license $file";
}

sub fix_license {
    my $lines = 0;
    while (<UPDATE>) {
       my $l = $_;
       if (($lines++ < 20) && ($l =~ m!//\s+http://www.apache.org!)) {
           $l = '// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf' . "\n";
       }
       print TMP $l;
    }
}

