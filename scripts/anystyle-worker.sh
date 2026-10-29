#!/bin/bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
ruby_bin="${ANYSTYLE_RUBY:-}"
if [[ -z "$ruby_bin" ]]; then
  if [[ -x /opt/homebrew/opt/ruby/bin/ruby ]]; then ruby_bin=/opt/homebrew/opt/ruby/bin/ruby
  else ruby_bin="$(command -v ruby)"; fi
fi
export GEM_HOME="${ANYSTYLE_GEM_HOME:-$HOME/.local/share/bibliography-anystyle/gems}"
export GEM_PATH="$GEM_HOME"
exec "$ruby_bin" "$script_dir/anystyle-worker.rb"
