#!/bin/bash
set -euo pipefail
ruby_bin="${ANYSTYLE_RUBY:-}"
if [[ -z "$ruby_bin" ]]; then
  if [[ -x /opt/homebrew/opt/ruby/bin/ruby ]]; then ruby_bin=/opt/homebrew/opt/ruby/bin/ruby
  else ruby_bin="$(command -v ruby)"; fi
fi
gem_home="${ANYSTYLE_GEM_HOME:-$HOME/.local/share/bibliography-anystyle/gems}"
gem_bin="$(dirname "$ruby_bin")/gem"
"$gem_bin" install anystyle --version 1.6.0 --install-dir "$gem_home" --no-document
"$gem_bin" install anystyle-data --version 1.3.0 --install-dir "$gem_home" --no-document
"$gem_bin" install anystyle-cli --version 1.5.0 --install-dir "$gem_home" --no-document
echo "AnyStyle установлен в $gem_home; системные gems не изменены."
