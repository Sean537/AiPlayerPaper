#!/bin/sh
# 薄包装：真正的构建走 Gradle（paper-plugin/build.gradle），这样 jar 名、版本号、
# UTF-8 编码都由构建文件统一管，不会再出现"源码是中文却按平台默认编码编译"这种坑。
#
# 想跳过 Gradle 手工编译的话，需要先把 paper-api 及其传递依赖放进 libs/：
#   mkdir -p libs && <把 paper-api 的 jar 和它的依赖都拷进来>
#   ./build.sh --raw
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ "${1:-}" = "--raw" ]; then
  JAVAC=${JAVAC:-javac}
  JAR=${JAR:-jar}
  [ -d "$ROOT/libs" ] || { echo "需要 --raw 模式时，请把 paper-api 及依赖放进 paper-plugin/libs/" >&2; exit 2; }
  VERSION=$(sed -n 's/^version:[[:space:]]*["'\'']\{0,1\}\([^"'\''[:space:]]*\).*/\1/p' "$ROOT/src/main/resources/plugin.yml")
  rm -rf "$ROOT/build/classes" "$ROOT/dist"
  mkdir -p "$ROOT/build/classes" "$ROOT/dist"
  # -encoding UTF-8 不能省：源码里有大量中文，缺了它 javac 仍会成功，但输出是乱码。
  "$JAVAC" --release 21 -encoding UTF-8 -cp "$ROOT/libs/*" -d "$ROOT/build/classes" \
    $(find "$ROOT/src/main/java" -name '*.java' -print)
  cp -R "$ROOT/src/main/resources/." "$ROOT/build/classes/"
  "$JAR" --create --file "$ROOT/dist/AiPlayerPaper-${VERSION:-dev}.jar" -C "$ROOT/build/classes" .
  printf '%s\n' "$ROOT/dist/AiPlayerPaper-${VERSION:-dev}.jar"
  exit 0
fi

if [ -x "$ROOT/gradlew" ]; then
  exec "$ROOT/gradlew" -p "$ROOT" build
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle -p "$ROOT" build
fi
echo "找不到 gradlew 或 gradle。请安装 Gradle 8+，或改用 ./build.sh --raw（需自备 libs/）。" >&2
exit 2