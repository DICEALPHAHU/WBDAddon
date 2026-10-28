#!/usr/bin/env bash
# 不依赖 Maven 的构建脚本：javac --release 17 + jar
#
# 两个版本号：
#   对外版本 public-version.txt（玩家看到的，写进 plugin.yml）
#   内部版本 pom.xml 的 <version>（每次更新自增，只在产物名里体现）
#
# 产物：target/WBDAddon-<对外>-<内部>-<git短哈希>-<时间戳>.jar
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$REPO/target/build"
M2="${M2_REPO:-$HOME/.m2/repository}"

PAPER="$M2/io/papermc/paper/paper-api/1.20.1-R0.1-SNAPSHOT/paper-api-1.20.1-R0.1-SNAPSHOT.jar"
ANNOT="$M2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar"
[ -f "$ANNOT" ] || ANNOT="$M2/org/jetbrains/annotations-java5/24.1.0/annotations-java5-24.1.0.jar"

if [ ! -f "$PAPER" ]; then
  echo "找不到 paper-api，请先执行 mvn dependency:resolve，或用 M2_REPO 指定本地仓库" >&2
  exit 1
fi
for req in "$REPO/libs/wbd.jar" "$REPO/libs/taczspigotbridge.jar"; do
  if [ ! -f "$req" ]; then
    echo "缺少编译期依赖：$req（见 libs/README.md）" >&2
    exit 1
  fi
done

CP="$PAPER:$REPO/libs/wbd.jar:$REPO/libs/taczspigotbridge.jar:$ANNOT"
for j in "$M2"/net/kyori/*/4.14.0/*.jar; do
  case "$j" in *sources*) ;; *) CP="$CP:$j";; esac
done
CP="$CP:$M2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"
CP="$CP:$M2/net/kyori/option/1.1.0/option-1.1.0.jar"
CP="$CP:$M2/net/md-5/bungeecord-chat/1.20-R0.2/bungeecord-chat-1.20-R0.2.jar"
CP="$CP:$M2/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar"
[ -f "$REPO/libs/ProtocolLib-5.4.0.jar" ] && CP="$CP:$REPO/libs/ProtocolLib-5.4.0.jar"

rm -rf "$OUT" && mkdir -p "$OUT/classes"
find "$REPO/src/main/java" -name '*.java' | sort > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT/classes" "@$OUT/sources.txt"

PUBLIC=$(tr -d '[:space:]' < "$REPO/public-version.txt")
INTERNAL=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$REPO/pom.xml" | head -1)
SHA=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo nogit)
STAMP=$(date +%Y%m%d-%H%M)

cp -r "$REPO/src/main/resources/." "$OUT/classes/"
sed -i "s/\${project.version}/$PUBLIC/" "$OUT/classes/plugin.yml"
printf 'git.commit.id.abbrev=%s\ngit.build.time=%s\nversion.public=%s\nversion.internal=%s\n' \
  "$SHA" "$STAMP" "$PUBLIC" "$INTERNAL" > "$OUT/classes/git.properties"

JAR="$REPO/target/WBDAddon-$PUBLIC-$INTERNAL-$SHA-$STAMP.jar"
mkdir -p "$REPO/target"
( cd "$OUT/classes" && jar --create --file "$JAR" . )
echo "已构建：$JAR（对外 $PUBLIC / 内部 $INTERNAL）"
