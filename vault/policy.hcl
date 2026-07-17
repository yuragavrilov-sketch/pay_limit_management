# Vault ACL policy for pay-limit-management.
# KV v2 mount `kv`; metadata is intentionally not granted.
path "kv/data/test/pay/services/pay-limit-management" {
  capabilities = ["read"]
}
