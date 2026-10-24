# frozen_string_literal: true

# Host-only contract structure/parity check; uses Ruby's standard library.
require 'json'
require 'psych'

root = File.expand_path('..', __dir__)
api = Psych.safe_load(File.read(File.join(root, 'contracts/api/cloud-api-v1.yaml')))
raise 'Expected OpenAPI 3.1' unless api.fetch('openapi') == '3.1.0'
schemes = api.fetch('components').fetch('securitySchemes')

check_security = lambda do |requirements|
  raise 'Security must be an array' unless requirements.is_a?(Array)
  requirements.each do |requirement|
    raise 'Security requirement must be a map' unless requirement.is_a?(Hash)
    requirement.each do |scheme, scopes|
      raise "Unknown security scheme #{scheme}" unless schemes.key?(scheme)
      raise 'Security scopes must be an array' unless scopes.is_a?(Array)
    end
  end
end
check_security.call(api.fetch('security'))
tags = api.fetch('tags').map { |tag| tag.fetch('name') }
operations = []
api.fetch('paths').each_value do |path|
  path.each do |method, operation|
    next unless %w[get post put patch delete options head trace].include?(method)
    operations << operation.fetch('operationId')
    raise 'Unknown operation tag' unless (operation.fetch('tags') - tags).empty?
    check_security.call(operation.fetch('security', api.fetch('security')))
  end
end
raise 'Duplicate operation IDs' unless operations.uniq == operations
references = 0
walk = lambda do |value|
  case value
  when Hash
    if value.key?('$ref')
      ref = value.fetch('$ref')
      raise "External reference not expected: #{ref}" unless ref.start_with?('#/')
      ref.delete_prefix('#/').split('/').reduce(api) { |node, key| node.fetch(key.gsub('~1', '/').gsub('~0', '~')) }
      references += 1
    end
    value.each_value { |child| walk.call(child) }
  when Array
    value.each { |child| walk.call(child) }
  end
end
walk.call(api)
shared = JSON.parse(File.read(File.join(root, 'contracts/fixtures/cp01-fixtures.json')))
server = JSON.parse(File.read(File.join(root, 'server/test/fixtures/cp01-fixtures.json')))
raise 'Android/server fixture drift' unless shared == server
puts "PASS: #{operations.length} operations, #{references} resolved references, security/tag structure and shared fixture parity"
