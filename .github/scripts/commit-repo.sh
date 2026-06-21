#!/bin/bash
set -e

git config --global user.email "295578635+eleanorlydie-wq@users.noreply.github.com"
git config --global user.name "eleanorlydie-wq"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    curl https://purge.jsdelivr.net/gh/eleanorlydie-wq/extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
