export type PasswordPolicyIssueCode =
  | 'length-short'
  | 'length-long'
  | 'upper'
  | 'lower'
  | 'digit'
  | 'symbol';

export interface PasswordPolicyIssue {
  code: PasswordPolicyIssueCode;
  met: boolean;
}

export function passwordPolicyChecks(value: unknown): PasswordPolicyIssue[] {
  const text = typeof value === 'string' ? value : '';
  return [
    {
      code: text.length > 256 ? 'length-long' : 'length-short',
      met: text.length >= 12 && text.length <= 256,
    },
    { code: 'upper', met: /\p{Lu}/u.test(text) },
    { code: 'lower', met: /\p{Ll}/u.test(text) },
    { code: 'digit', met: /\p{Nd}/u.test(text) },
    { code: 'symbol', met: /[^\p{L}\p{N}]/u.test(text) },
  ];
}

export function passwordPolicyIssues(value: unknown): PasswordPolicyIssue[] {
  return passwordPolicyChecks(value).filter((issue) => !issue.met);
}

export function passwordMeetsPolicy(value: unknown): value is string {
  return typeof value === 'string' && passwordPolicyIssues(value).length === 0;
}
