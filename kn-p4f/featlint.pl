# perl port of scripts/verify-features.sh (BSD awk can't run it); run from a repo root
use utf8; binmode STDOUT, ':utf8'; my $bad=0; my %topic = map { $_ => 1 } map { s#.*/##r } glob 'docs/topics/*';
open my $f, '<:utf8', 'docs/topics/supported-features.md' or die; my %seen; my $mode=0;
while (<$f>) { chomp; s/$//; if ($_ eq '') { $mode=0; next } next unless /^\|/; my $sep = $_ =~ s/[|:\- \t]//gr; next if $sep eq '';
 my $r=$_; $r =~ s/\\\|/\x01/g; $r=~s/^\|//; $r=~s/\|\s*$//; my @c = map { s/^\s+|\s+$//gr } split /\|/, $r, -1;
 if ($c[0] eq 'Kotlin') { $mode=6; next } if ($c[0] eq 'Glyph') { $mode=2; next } next if $mode==2;
 if (@c!=5) { print "$.: row has ".scalar(@c)." cells\n"; $bad++; next }
 if (length($c[3])>200) { print "$.: Notes is ".length($c[3])." characters (max 200)\n"; $bad++ }
 for my $l (split / · /, $c[4]) { next if $l eq ''; if ($l !~ /^\[[^\]\[]+\]\(([A-Za-z0-9._-]+\.md)\)$/) { print "$.: Docs entry \"$l\" is not a [text](name.md) link\n"; $bad++ } elsif (!$topic{$1}) { print "$.: Docs links missing $1\n"; $bad++ } }
 my $k = join "\0", @c[0..2]; if ($seen{$k}) { print "$.: duplicate row\n"; $bad++ } else { $seen{$k}=$. }
}
print $bad ? "features check FAILED ($bad)\n" : "features check OK\n"; exit($bad?1:0);
