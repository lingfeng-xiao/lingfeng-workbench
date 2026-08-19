"""Authenticated-principal contract for isolated policy tests."""

from __future__ import annotations

from dataclasses import dataclass

from .enums import ActorRole
from .validation import identifier

_AUTHENTICATED = object()


@dataclass(frozen=True, slots=True)
class PrincipalClaims:
    principal_id: str
    role: ActorRole
    node_id: str | None = None
    runtime_id: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "principal_id", identifier(self.principal_id, "principal_id")
        )
        object.__setattr__(self, "role", ActorRole(self.role))
        if self.node_id is not None:
            object.__setattr__(self, "node_id", identifier(self.node_id, "node_id"))
        if self.runtime_id is not None:
            object.__setattr__(
                self, "runtime_id", identifier(self.runtime_id, "runtime_id")
            )
        if self.role is ActorRole.AGENT_RUNTIME and (
            self.node_id is None or self.runtime_id is None
        ):
            raise ValueError("runtime claims require node_id and runtime_id")


class AuthenticatedPrincipal:
    __slots__ = ("principal_id", "role", "node_id", "runtime_id", "_seal")

    def __init__(self, claims: PrincipalClaims, *, _seal: object | None = None) -> None:
        if _seal is not _AUTHENTICATED:
            raise PermissionError("principal must come from a verified identity provider")
        self.principal_id = claims.principal_id
        self.role = claims.role
        self.node_id = claims.node_id
        self.runtime_id = claims.runtime_id
        self._seal = _seal


class IsolatedIdentityProvider:
    """Synthetic identity provider; deliberately restricted to isolated tests."""

    def __init__(self, claims: tuple[PrincipalClaims, ...], *, isolated: bool) -> None:
        if not isolated:
            raise RuntimeError("the synthetic identity provider is test-only")
        self._claims = {claim.principal_id: claim for claim in claims}

    def authenticate(self, principal_id: str) -> AuthenticatedPrincipal:
        normalized = identifier(principal_id, "principal_id")
        try:
            claims = self._claims[normalized]
        except KeyError as exc:
            raise PermissionError("authentication failed") from exc
        return AuthenticatedPrincipal(claims, _seal=_AUTHENTICATED)


def require_authenticated(principal: AuthenticatedPrincipal) -> None:
    if (
        not isinstance(principal, AuthenticatedPrincipal)
        or principal._seal is not _AUTHENTICATED
    ):
        raise PermissionError("authenticated principal required")
