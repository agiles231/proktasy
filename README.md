# proktasy
An Okta REST API proxy that solves rate limiting woes.

## Rate limiting
Okta implements rate limiting for different endpoints. Okta makes it pretty easy for a single
application to avoid rate limiting cooldowns through the use of Http headers. The headers
provide the state of rate limiting for the endpoint that was just hit.

## The problem
As mentioned, for a single application, dealing with rate limiting is easy. For multiple
applications, not so much. When one application changes the state of the rate limiting
for an endpoint, another application will not know the change that has occurred. For
example, suppose an application a1 is about to hit the endpoint "api/v1/users" (referred to
as e1 throughout this example). If the rate limit r = 600/minute and another application a2
has hit e1 600 times this minute, a1 has no way of knowing this and will inadvertently
trigger a cooldown.

## The Solution
With proktasy, a1 would go through proktasy and proktasy would not make the request until
a new rate limiting period begins.

## Solution short-comings
Currently, this solution is designed with legacy rates in mind, and no attention has given
to new rates. It may very well work with them, but I have no way of testing this.

Also, the code probably needs a refactor. Feel free to do so and make a pull request.
