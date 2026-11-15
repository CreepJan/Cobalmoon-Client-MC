//! Evaluation of the `rules` arrays used in Mojang version JSON (libraries and arguments).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Rule {
    pub action: Action,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os: Option<OsRule>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct OsRule {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arch: Option<String>,
}

/// What the rules are evaluated against.
#[derive(Debug, Clone)]
pub struct Env {
    /// Mojang OS name: "windows", "osx" or "linux".
    pub os: &'static str,
    /// Mojang arch name: "x86" (32-bit), "x86_64", "arm64".
    pub arch: &'static str,
    pub features: HashMap<String, bool>,
}

impl Env {
    pub fn current() -> Self {
        let os = if cfg!(target_os = "windows") {
            "windows"
        } else if cfg!(target_os = "macos") {
            "osx"
        } else {
            "linux"
        };
        let arch = if cfg!(target_arch = "aarch64") {
            "arm64"
        } else if cfg!(target_arch = "x86") {
            "x86"
        } else {
            "x86_64"
        };
        Env { os, arch, features: HashMap::new() }
    }
}

fn matches(rule: &Rule, env: &Env) -> bool {
    if let Some(os) = &rule.os {
        if let Some(name) = &os.name {
            if name != env.os {
                return false;
            }
        }
        if let Some(arch) = &os.arch {
            if arch != env.arch {
                return false;
            }
        }
    }
    if let Some(features) = &rule.features {
        for (k, v) in features {
            if env.features.get(k).copied().unwrap_or(false) != *v {
                return false;
            }
        }
    }
    true
}

/// Mojang semantics: no rules = allowed; otherwise start disallowed and let the last matching rule decide.
pub fn allowed(rules: &[Rule], env: &Env) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut result = false;
    for r in rules {
        if matches(r, env) {
            result = r.action == Action::Allow;
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env(os: &'static str) -> Env {
        Env { os, arch: "x86_64", features: HashMap::new() }
    }

    fn os_rule(action: Action, name: &str) -> Rule {
        Rule { action, os: Some(OsRule { name: Some(name.into()), arch: None }), features: None }
    }

    #[test]
    fn empty_rules_allow() {
        assert!(allowed(&[], &env("linux")));
    }

    #[test]
    fn allow_only_on_os() {
        let rules = vec![os_rule(Action::Allow, "windows")];
        assert!(allowed(&rules, &env("windows")));
        assert!(!allowed(&rules, &env("osx")));
    }

    #[test]
    fn allow_all_except_os() {
        let rules = vec![Rule { action: Action::Allow, os: None, features: None }, os_rule(Action::Disallow, "osx")];
        assert!(allowed(&rules, &env("linux")));
        assert!(!allowed(&rules, &env("osx")));
    }

    #[test]
    fn features_default_to_false() {
        let rules: Vec<Rule> = serde_json::from_str(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#).unwrap();
        assert!(!allowed(&rules, &env("linux")));
        let mut e = env("linux");
        e.features.insert("is_demo_user".into(), true);
        assert!(allowed(&rules, &e));
    }

    #[test]
    fn arch_rule() {
        let rules: Vec<Rule> = serde_json::from_str(r#"[{"action":"allow","os":{"arch":"x86"}}]"#).unwrap();
        assert!(!allowed(&rules, &env("windows")));
    }
}
