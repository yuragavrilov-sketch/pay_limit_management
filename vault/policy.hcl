# Vault ACL policy for pay-limit-management.
# KV v1 mount `pay`, layout pay/{env}/{app}-{secret}.
path "pay/test/pay-limit-management-*" {
  capabilities = ["read"]
}
