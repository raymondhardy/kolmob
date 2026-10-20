#!/usr/bin/env python3
"""Generates the Android GitManager from upstream's: keeps all upstream logic (sync, conflicts,
dependencies, listing) and replaces only the members that need JGit with GitHub-archive versions
from tools/git_android_members.java.txt. Run by tools/sync-upstream.sh after the upstream copy."""
import os, re, sys
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(HERE, 'kolmafia-core/src/main/java/net/sourceforge/kolmafia/scripts/git/GitManager.java')
s = open(PATH, encoding='utf-8').read()

REMOVE = [  # (regex matched against the member header incl. preceding javadoc)
    r'public static boolean clone\(\s*String repoUrl,\s*String branch\s*\)',
    r'public static boolean update\(\s*String project\s*\)',
    r'static boolean rebase\(', r'static ObjectId getCurrentCommit\(', r'static AbstractTreeIterator getCurrentCommitTree\(',
    r'static void printCommitMessages\(', r'static ZonedDateTime getCommitDate\(', r'static String getAuthor\(',
    r'public static Optional<GitInfo> getInfo\(', r'public static boolean isValidRepo\(', r'public static boolean isUpToDate\(',
    r'class MafiaProgressMonitor', r'public static RepoDetails getRepoDetails\(',
]
i = s.index('public class GitManager'); j = s.index('{', i)
depth = 1; pos = j + 1; members = []; last = j + 1
while pos < len(s) and depth > 0:
    c = s[pos]
    if c == '{':
        if depth == 1:
            hs = max(s.rfind(';', 0, pos), s.rfind('}', 0, pos), j) + 1
            header = s[hs:pos]
        depth += 1
    elif c == '}':
        depth -= 1
        if depth == 1: members.append((header, hs, pos + 1))
    pos += 1
cuts = []
for pat in REMOVE:
    hits = [(h, a, b) for h, a, b in members if re.search(pat, h)]
    if len(hits) != 1:
        sys.exit(f'gen_git_override: expected exactly one member for {pat!r}, found {len(hits)} (upstream changed?)')
    cuts.append(hits[0][1:])
for a, b in sorted(cuts, reverse=True):
    s = s[:a] + s[b:]
s = re.sub(r'^import org\.eclipse\.jgit[^\n]*\n', '', s, flags=re.M)
# Files.readString/writeString are missing from Android's runtime: use byte-based helpers
s = s.replace('Files.readString(', 'readUtf8(').replace('Files.writeString(', 'writeUtf8(')
# widen visibility of helpers the Android SVNManager shares
for old, new in [
    ('private static boolean sync(Path projectPath)', 'public static boolean sync(Path projectPath)'),
    ('private static void invalidateProjectsSyncPaths()', 'public static void invalidateProjectsSyncPaths()'),
    ('private static void deleteOldFile(Path path)', 'public static void deleteOldFile(Path path)'),
]:
    if old not in s: sys.exit('gen_git_override: cannot widen ' + old)
    s = s.replace(old, new, 1)
# inject the Android members before the final closing brace of the class
k = s.rstrip().rfind('}')
s = s[:k] + open(os.path.join(HERE, 'tools/git_android_members.java.txt'), encoding='utf-8').read() + '}\n'
open(PATH, 'w', encoding='utf-8').write(s)
print('GitManager: replaced', len(REMOVE), 'JGit-backed members')
