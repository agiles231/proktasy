# proktasy
An Okta REST API proxy that solves rate limiting woes.

## Rate limiting
Okta implements rate limiting for different endpoints. Okta makes it pretty easy for a single
application to avoid reducing "remaining" request to 0 through the use of Http headers. The headers
provide the state of rate limiting for the endpoint that was just hit.

## The problem
As mentioned, for a single application, dealing with rate limiting is easy. For multiple
applications, not so much. When one application changes the state of the rate limiting
for an endpoint, another application will not know the change that has occurred. For
example, suppose there are 10 applications, a0, a1, ..., a9, that are all hitting the same
endpoint (or at least same as far as Okta is concerned with rate limiting). Also, for
simplicity, assume they hit the endpoint sequentially. So according to the headers received
by each app from its last request, they each have a remaining amount r0, r1, ..., r9.
Suppose r0 = 12, r1 = 11, ..., r9 = 3. Now, if each app is coded in such a way that they
attempt to make sure that at least 5 are remaining (so that Okta's own calls to the API
from the frontend succeed), then apps a7, a8, and a9 will not attempt their next call
until the reset time that they were given in Okta's reponse headers. However, apps a0,
a1, ..., a6 will all attempt their next call and apps a4, a5, a6 will all be returned
an http 429. This is sub-optimal because now some frontend administrators may have to
wait to complete tasks they wish to complete.

## The Solution
With proktasy, a0, ..., a9 would go through proktasy and proktasy would not reduce the
"remainings" below a threshold (determined by configuration).

## Configuration
All configuration is done through web.xml.

`targetUri` : where proktasy is proxying to. i.e. `https://domain.okta.com`

`proktasy.maxConcurrentRequests` : How many requests can be executed at the same time. For legacy, this is 75

`proktasy.rateLimitRegexes` : These define which rate limit will be applied to a given request

`proktasy.proxyHost` : Your company's proxy, if needed

`proktasy.proxyPort` : Your company's proxy port, if needed

`proktasy.bufferRequests` : For each rate limit bucket, this many will be subtracted to allow leniency (in case someone else is using same endpoint). Must be greater than or equal to 0.

`proktasy.bufferConcurrency` : `proktasy.maxConcurrentRequests` - `proktasy.bufferConcurrency` will determine maximum concurrent requests allowed by proktasy.

`proktasy.targetHost` : Okta host. Used for substituting headers in responses for Okta.

`proktasy.proktasyHost` : Where this application is hosted. Used for substituting headers in responses for Okta.



Additional configuration is required for dependencies.

See https://github.com/mitre/HTTP-Proxy-Servlet for additional details

## Solution short-comings
Currently, this solution is designed with legacy rates in mind, and no attention has given
to new rates. It may very well work with them, but I have no way of testing this.

Also, the code probably needs a refactor. Feel free to do so and make a pull request.
