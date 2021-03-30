/**
 *  Litter-Robot Manager
 *
 *  Copyright 2020 Nathan Spencer
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *  
 *  CHANGE HISTORY
 *  VERSION     DATE            NOTES
 *  1.0.0       2019-04-10      Initial release
 *  1.0.1       2019-04-23      Attempt to re-auth twice if first re-auth fails. Also adds support for resetting gauge
 *  1.0.2       2020-02-05      Adjustments to reauthorization fail logic. Prevent hitting API (except for login) when
 *                              not logged in. Update robots with [Disconnected] status when no longer logged in.
 *  1.0.3       2021-03-30      Fix to use newer API. Add settings for apiKey, clientId and clientSecret with defaults.
 *
 */

definition(
    name: "Litter-Robot Manager",
    namespace: "natekspencer",
    author: "Nathan Spencer",
    description: "Access and control your Litter-Robot robots.",
    category: "Pets",
    iconUrl: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot.png",
    iconX2Url: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png",
    singleInstance: true
) {
    appSetting "apiKey"
    appSetting "clientId"
    appSetting "clientSecret"
}

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "authResultPage")
}

def mainPage() {
    def robots = [:]
    // Get robots if we don't have them already
    if ((state.robots?.size()?:0) == 0 && state.accessToken?.trim()) {
        getLitterRobots()
    }
    if (state.robots) {
        robots = state.robots
        robots.sort { it.value }
    }
        
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        if (robots) {
            section("Select which Litter-Robots to use:") {
                input(name: "robots", type: "enum", title: "Litter-Robots", required: false, multiple: true, metadata: [values: robots])
            }
            section("How frequently do you want to poll the Litter-Robot cloud for changes? (Use a lower number if you care about trying to capture and respond to \"cleaning\" events as they happen)") {
                input(name: "pollingInterval", title: "Polling Interval (in Minutes)", type: "enum", required: false, multiple: false, defaultValue: 5, description: "5", options: ["1", "5", "10", "15", "30"])
            }
        }
        section("Litter-Robot Authentication") {
            href("authPage", title: "Litter-Robot API Authorization", description: "${state.credentialStatus ? "${state.credentialStatus}${state.loggedIn ? "" : " - ${state.loginResponse}"}\n" : ""}Click to enter Litter-Robot credentials")
        }
        section ("Name this instance of ${app.name}") {
            label name: "name", title: "Assign a name", required: false, defaultValue: app.name, description: app.name, submitOnChange: true
        }
    }
}

def authPage() {
    dynamicPage(name: "authPage", nextPage: "authResultPage", uninstall: false, install: false) {
        section("Litter-Robot Credentials") {
            input("username", "text", title: "Username", description: "Litter-Robot Username", required: true)
            input("password", "password", title: "Password", description: "Litter-Robot Password", required: true)
        }
    }
}

def authResultPage() {
    log.info "Attempting login with specified credentials..."
    
    if (doLogin()) {
        state.userId = doGet("/users").data.user.userId
    }
    
    // Check if login was successful
    if (state.accessToken == null) {
        dynamicPage(name: "authResultPage", nextPage: "authPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please check your credentials and try again.")
            }
        }
    } else {
        dynamicPage(name: "authResultPage", nextPage: "mainPage", uninstall: false, install: false) {
            section("Login Successful") {
                paragraph ("Please click next to continue setting up your Litter-Robot.")
            }
        }
    }
}


boolean doLogin(){
    def loggedIn = false
    
    state.loginAttempt = (state.loginAttempt ?: 0) + 1

    def params = [
        uri: "https://autopets.sso.iothings.site/oauth/token",
        headers: [
            "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
            "Accept": "application/json",
        ],
        body: [
            client_id: appSettings.clientId ?: "IYXzWN908psOm7sNpe4G.ios.whisker.robots",
            client_secret: appSettings.clientSecret ?: "C63CLXOmwNaqLTB2xXo6QIWGwwBamcPuaul",
            grant_type: "password",
            username: username,
            password: password
        ]
    ]

    def response

    try {
        httpPost(params) {resp->
            response = resp
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.info e
        response = e.response
    } catch (e) {
        log.error "Something went wrong: ${e}"
        response = [error: e.message]
    }
    
    switch (response.status) {
        case 401:
            state.loggedIn = false
            state.accessToken = null
            state.refreshToken = null
            state.robots = null
            break
        case 200:
            state.loggedIn = true
            state.accessToken = response.data.access_token
            state.refreshToken = response.data.refresh_token
            // state.loginDate = toStDateString(parseLrDate(response.data._created))
            state.remove("loginAttempt")
            break
        default:
            log.debug response.data
            state.loggedIn = false
            state.accessToken = null
            state.refreshToken = null
            state.robots = null
            break
    }

    loggedIn = state.loggedIn
    state.credentialStatus = loggedIn ? "[Connected]" : "[Disconnected]"
    loggedIn
}

def reAuth() {
    if (!doLogin()) {
        runIn(60 * state.loginAttempt * state.loginAttempt, reAuth) // timeout or other login issue occurred, attempt again with exponential backoff
        getChildDevices().each {
            it.parseUnitStatus(state.credentialStatus, null)
            it.sendEvent(name: "robotStatusText", value: state.loginResponse)
        }
    }
}

// Get the list of Litter-Robots
def getLitterRobots() {
    def data = doGet("/users/${state.userId}/robots").data
    // save in state so we can re-use in settings
    def robots = [:]
    data.each {
        robots[[app.id, it.litterRobotId].join('.')] = it.litterRobotNickname
    }
    state.robots = robots
    data
}

def doGet(urlPath) {
    return doCallout("GET", urlPath, null, null)
}

def doGet(urlPath, queryParams) {
    return doCallout("GET", urlPath, null, queryParams)
}

def doCallout(calloutMethod, urlPath, calloutBody) {
    return doCallout(calloutMethod, urlPath, calloutBody, null)
}


def doCallout(calloutMethod, urlPath, calloutBody, queryParams){
    if (state.loggedIn) { // prevent unauthorized calls
        log.info "\"${calloutMethod}\"-ing \"${urlPath}\""
    
        def params = [
            uri: "https://v2.api.whisker.iothings.site",
            path: urlPath,
            query: queryParams,
            headers: [
                "Content-Type": "application/json",
                "x-api-key": appSettings.apiKey ?: "p7ndMoj61npRZP5CVz9v4Uj0bG769xy6758QRBPb",
                Authorization: "Bearer ${state.accessToken}"
            ],
            body: calloutBody
        ]
        
        try {
            switch (calloutMethod) {
                case "GET":
                    httpGet(params) {resp->
                        return resp
                    }
                    break
                case "PATCH":
                    params.headers["x-http-method-override"] = "PATCH"
                    // NOTE: break is purposefully missing so that it falls into the next case and "POST"s
                case "POST":
                    httpPostJson(params) {resp->
                        return resp
                    }
                    break
                default:
                    log.error "unhandled method"
                    return [error: "unhandled method"]
                    break
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            log.info e
            return e.response
        } catch (e) {
            log.error "Something went wrong: ${e}"
            return [error: e.message]
        }
    } else {
        log.info "skipping request since the user is not currently logged in"
        return []
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    // Tokens expire every 24 hours. Schedule to reauthorize every day
    if(state.loginDate?.trim()) schedule(parseStDate(state.loginDate), reAuth)

    def delete = getChildDevices().findAll { !settings.robots?.contains(it.deviceNetworkId) }
    delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
    
    def childDevices = []
    settings.robots.each {deviceId ->
        try {
            def childDevice = getChildDevice(deviceId)
            if(!childDevice) {
                log.info "Adding device: ${state.robots[deviceId]} [${deviceId}]"
                childDevice = addChildDevice(app.namespace, "Litter-Robot", deviceId, location.hubs[0]?.id, [label: state.robots[deviceId], completedSetup: true])
            }
            childDevices.add(childDevice)
        } catch (e) {
            log.error "Error creating device: ${e}"
        }
    }
    
    // set up polling only if we have child devices
    if(childDevices.size() > 0) {
        pollChildren()
        "runEvery${pollingInterval}Minute${pollingInterval != "1" ? 's' : ''}"("pollChildren")
    } else unschedule(pollChildren)
}

def pollChildren() {
    log.info "polling..."
    def devices = getChildDevices()
    if (devices.size() == 0) {
        log.info "no children to update: skipping polling"
    } else {
        def robots = getLitterRobots()
        devices.each {
            def dni = it.deviceNetworkId
            def deviceData = robots.find { [app.id, it.litterRobotId].join('.') == dni }
            if (deviceData) {
                it.parseEventData(deviceData)
            }
        }
    }
}

def dispatchCommand(litterRobotId, command) {
    log.info "dispatch command ${command} for ${litterRobotId}"
    def resp = doCallout("POST", "/users/${state.userId}/robots/${litterRobotId}/dispatch-commands", [command: command as String])
    resp.data
}

def getActivity(litterRobotId, limit=10) {
    def resp = doGet("/users/${state.userId}/robots/${litterRobotId}/activity", [limit: limit])
    resp.data.activities
}

def resetDrawerGauge(litterRobotId, params) {
    def resp = doCallout("PATCH", "/users/${state.userId}/robots/${litterRobotId}", [litterRobotNickname: params.name, cycleCapacity: params.capacity, cycleCount: 0, cyclesAfterDrawerFull: 0])
    resp.data
}

def isoFormat() {
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
}

def toStDateString(date) {
    date.format(isoFormat())
}

def parseStDate(dateStr) {
    dateStr?.trim() ? timeToday(dateStr) : null
}

def parseLrDate(dateStr) {
    dateStr?.trim() ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS", dateStr?.substring(0,23)) : null
}