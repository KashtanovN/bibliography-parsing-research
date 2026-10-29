#!/usr/bin/env ruby
# JSON-lines bridge. Receives only a citation, never annotations or formatting context.
require 'json'
require 'digest'
require 'anystyle'

STDOUT.sync = true
parser = AnyStyle::Parser.new(threads: 1)
packages = Gem.loaded_specs.sort.to_h.transform_values { |spec| spec.version.to_s }
assets = %w[anystyle anystyle-data wapiti namae bibtex-ruby latex-decode].to_h do |name|
  spec = Gem.loaded_specs.fetch(name)
  digest = Digest::SHA256.new
  Dir.glob(File.join(spec.full_gem_path, '**', '*')).sort.each do |file|
    next unless File.file?(file)
    digest << file.delete_prefix(spec.full_gem_path + '/') << "\0" << Digest::SHA256.file(file).hexdigest << "\n"
  end
  [name, digest.hexdigest]
end
puts JSON.generate({protocol: 'anystyle-jsonl-v1', version: AnyStyle::VERSION,
  rubyVersion: RUBY_DESCRIPTION, dependencies: packages, assetsSha256: assets,
  modelSha256: Digest::SHA256.file(parser.model.path).hexdigest,
  workerSha256: Digest::SHA256.file(__FILE__).hexdigest, threads: 1,
  training: 'stock model; no local training', input: 'one raw citation per request'})
STDIN.each_line do |line|
  begin
    request = JSON.parse(line)
    citation = request.fetch('citation')
    raise ArgumentError, 'citation must be a string' unless citation.is_a?(String)
    # Embedded line breaks are whitespace inside this single reference, not new records.
    result = parser.parse(citation.gsub(/[\r\n]+/, ' '))
    puts JSON.generate({result: result})
  rescue StandardError => e
    puts JSON.generate({error: "#{e.class}: #{e.message}"})
  end
end
