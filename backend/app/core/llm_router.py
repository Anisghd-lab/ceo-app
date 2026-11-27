from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

import httpx

from app.db.database import Database


@dataclass
class LLMResponse:
    content: str
    model_used: str
    provider: str
    tokens_used: int = 0
    is_simulated: bool = False


PROVIDER_ENDPOINTS: dict[str, str] = {
    "openai": "https://api.openai.com/v1/chat/completions",
    "openrouter": "https://openrouter.ai/api/v1/chat/completions",
    "xai": "https://api.x.ai/v1/chat/completions",
    "groq": "https://api.groq.com/openai/v1/chat/completions",
    "deepseek": "https://api.deepseek.com/v1/chat/completions",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    "anthropic": "https://api.anthropic.com/v1/messages",
    "custom": "",
}

DEFAULT_MODELS: dict[str, str] = {
    "openai": "gpt-4o-mini",
    "openrouter": "anthropic/claude-3.5-sonnet",
    "xai": "grok-3-mini",
    "groq": "llama-3.3-70b-versatile",
    "deepseek": "deepseek-chat",
    "gemini": "gemini-2.5-flash",
    "anthropic": "claude-3-5-sonnet-latest",
}


class MultiLLMRouter:
    """Lightweight Multi-LLM Router supporting per-bot model selection, fallback chains, and token budgeting."""

    def __init__(self, db: Database) -> None:
        self.db = db

    async def get_providers_config(self) -> dict[str, Any]:
        saved = await self.db.get_setting("llm_providers", {}) or {}
        # Merge environment keys as non-persisted defaults
        env_map = {
            "openai": os.environ.get("OPENAI_API_KEY", ""),
            "openrouter": os.environ.get("OPENROUTER_API_KEY", ""),
            "xai": os.environ.get("XAI_API_KEY", ""),
            "groq": os.environ.get("GROQ_API_KEY", ""),
            "deepseek": os.environ.get("DEEPSEEK_API_KEY", ""),
            "gemini": os.environ.get("GEMINI_API_KEY", ""),
            "anthropic": os.environ.get("ANTHROPIC_API_KEY", ""),
        }
        merged: dict[str, Any] = {}
        for provider, env_key in env_map.items():
            cfg = dict(saved.get(provider, {}))
            if not cfg.get("api_key") and env_key:
                cfg["api_key"] = env_key
            cfg.setdefault("model", DEFAULT_MODELS.get(provider, ""))
            cfg.setdefault("base_url", PROVIDER_ENDPOINTS.get(provider, ""))
            merged[provider] = cfg
        if "custom" in saved:
            merged["custom"] = dict(saved["custom"])
        return merged

    async def available_providers(self) -> list[str]:
        cfgs = await self.get_providers_config()
        return [name for name, cfg in cfgs.items() if cfg.get("api_key")]

    async def _check_budget(self, company_id: str | None) -> None:
        if not company_id:
            return
        row = await self.db.fetchone(
            "SELECT daily_token_budget, tokens_used_today FROM companies WHERE id = ?",
            (company_id,),
        )
        if row and row["tokens_used_today"] >= row["daily_token_budget"]:
            raise RuntimeError(
                f"Company token budget exceeded ({row['tokens_used_today']}/{row['daily_token_budget']})"
            )

    async def _record_tokens(self, company_id: str | None, tokens: int) -> None:
        if not company_id or tokens <= 0:
            return
        await self.db.execute(
            "UPDATE companies SET tokens_used_today = tokens_used_today + ? WHERE id = ?",
            (tokens, company_id),
        )

    def _parse_model_spec(self, spec: str, cfgs: dict[str, Any]) -> list[tuple[str, str, dict[str, Any]]]:
        """Resolve 'provider:model' or 'auto' into an ordered fallback chain of (provider, model, cfg)."""
        chain: list[tuple[str, str, dict[str, Any]]] = []
        if spec and spec != "auto" and ":" in spec:
            prov, mod = spec.split(":", 1)
            prov = prov.strip().lower()
            if prov in cfgs and cfgs[prov].get("api_key"):
                chain.append((prov, mod.strip() or cfgs[prov].get("model", ""), cfgs[prov]))

        # Append all remaining configured providers as fallbacks
        priority = ["openrouter", "anthropic", "gemini", "openai", "xai", "deepseek", "groq", "custom"]
        for prov in priority:
            cfg = cfgs.get(prov, {})
            if cfg.get("api_key") and not any(p == prov for p, _, _ in chain):
                chain.append((prov, cfg.get("model") or DEFAULT_MODELS.get(prov, ""), cfg))
        return chain

    async def complete(
        self,
        messages: list[dict[str, str]],
        model_spec: str = "auto",
        company_id: str | None = None,
        json_mode: bool = False,
        temperature: float = 0.3,
        max_tokens: int = 2000,
    ) -> LLMResponse:
        await self._check_budget(company_id)
        cfgs = await self.get_providers_config()
        chain = self._parse_model_spec(model_spec, cfgs)

        last_error = ""
        for provider, model, cfg in chain:
            try:
                if provider == "anthropic":
                    resp = await self._call_anthropic(messages, model, cfg, temperature, max_tokens)
                else:
                    resp = await self._call_openai_compat(provider, messages, model, cfg, json_mode, temperature, max_tokens)
                await self._record_tokens(company_id, resp.tokens_used)
                return resp
            except Exception as exc:
                last_error = f"{provider}/{model}: {exc}"
                continue

        # No configured live LLM provider (or all failed) -> return signal so caller can run offline/demo fallback
        return LLMResponse(
            content="",
            model_used=f"offline-fallback ({last_error or 'no api key'})",
            provider="offline",
            tokens_used=0,
            is_simulated=True,
        )

    async def _call_openai_compat(
        self,
        provider: str,
        messages: list[dict[str, str]],
        model: str,
        cfg: dict[str, Any],
        json_mode: bool,
        temperature: float,
        max_tokens: int,
    ) -> LLMResponse:
        url = cfg.get("base_url") or PROVIDER_ENDPOINTS.get(provider, "")
        if not url:
            raise ValueError(f"Missing base_url for provider {provider}")
        headers = {
            "Authorization": f"Bearer {cfg['api_key']}",
            "Content-Type": "application/json",
        }
        if provider == "openrouter":
            headers["HTTP-Referer"] = "https://github.com/Anisghd-lab/foundry-os"
            headers["X-Title"] = "FoundryOS"

        payload: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if json_mode and provider in ("openai", "openrouter", "deepseek", "groq", "gemini"):
            payload["response_format"] = {"type": "json_object"}

        async with httpx.AsyncClient(timeout=45.0) as client:
            res = await client.post(url, headers=headers, json=payload)
            res.raise_for_status()
            data = res.json()
            text = data["choices"][0]["message"]["content"]
            usage = data.get("usage", {})
            tokens = int(usage.get("total_tokens", len(text) // 4))
            return LLMResponse(content=text, model_used=model, provider=provider, tokens_used=tokens)

    async def _call_anthropic(
        self,
        messages: list[dict[str, str]],
        model: str,
        cfg: dict[str, Any],
        temperature: float,
        max_tokens: int,
    ) -> LLMResponse:
        system_parts = [m["content"] for m in messages if m["role"] == "system"]
        user_msgs = [m for m in messages if m["role"] != "system"]
        payload: dict[str, Any] = {
            "model": model or "claude-3-5-sonnet-latest",
            "max_tokens": max_tokens,
            "temperature": temperature,
            "messages": user_msgs,
        }
        if system_parts:
            payload["system"] = "\n\n".join(system_parts)

        async with httpx.AsyncClient(timeout=45.0) as client:
            res = await client.post(
                cfg.get("base_url") or PROVIDER_ENDPOINTS["anthropic"],
                headers={
                    "x-api-key": cfg["api_key"],
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json=payload,
            )
            res.raise_for_status()
            data = res.json()
            text = "".join(block.get("text", "") for block in data.get("content", []) if block.get("type") == "text")
            usage = data.get("usage", {})
            tokens = int(usage.get("input_tokens", 0) + usage.get("output_tokens", 0))
            return LLMResponse(content=text, model_used=model, provider="anthropic", tokens_used=tokens)


def extract_json_object(raw: str) -> dict[str, Any] | None:
    """Safely parse a JSON object from an LLM response (handles ```json fences)."""
    if not raw:
        return None
    text = raw.strip()
    if "```" in text:
        parts = text.split("```")
        for chunk in parts:
            c = chunk.strip()
            if c.startswith("json"):
                c = c[4:].strip()
            if c.startswith("{") and c.endswith("}"):
                try:
                    return json.loads(c)
                except json.JSONDecodeError:
                    pass
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            return None
    return None
