#!/bin/bash
# LocalStack ready hook: the queue (with a dead-letter queue) and the bucket the API and worker use.
set -euo pipefail

awslocal sqs create-queue --queue-name tenantforge-exports-dlq >/dev/null
dlq_arn=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/tenantforge-exports-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name tenantforge-exports \
  --attributes "{\"VisibilityTimeout\":\"60\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null
awslocal s3 mb s3://tenantforge-files >/dev/null

echo "LocalStack ready: queue tenantforge-exports (+ DLQ), bucket tenantforge-files"
