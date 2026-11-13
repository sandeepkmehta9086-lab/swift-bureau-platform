import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { FakeBackend, VALID_TOTP } from "./fakeBackend";

export function renderApp(path = "/", options: Parameters<typeof userEvent.setup>[0] = {}) {
  const user = userEvent.setup(options);
  const utils = render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>
  );
  return { user, ...utils };
}

/** Signs in through the real login form. */
export async function signIn(
  user: ReturnType<typeof userEvent.setup>,
  loginId: string,
  password = loginId.startsWith("bureau") ? "ChangeMe_Bureau1!" : "ChangeMe_Bank1!"
) {
  const login = await screen.findByLabelText("Login ID");
  await user.clear(login);
  await user.type(login, loginId);
  const pw = screen.getByLabelText("Password");
  await user.clear(pw);
  await user.type(pw, password);
  await user.type(screen.getByLabelText("Authenticator code"), VALID_TOTP);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByRole("button", { name: "Sign out" });
}

export async function signOut(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Sign out" }));
  await screen.findByRole("heading", { name: "Bank user sign-in" });
}

export { FakeBackend, VALID_TOTP };
