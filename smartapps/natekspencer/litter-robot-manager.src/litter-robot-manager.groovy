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
 *
 */

 import groovy.transform.Field

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
)

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "authResultPage")
}

@Field static def authHost = "https://autopets.sso.iothings.site"
@Field static def apiHost = "https://v2.api.whisker.iothings.site"
@Field static def clientId = "IYXzWN908psOm7sNpe4G.ios.whisker.robots"
@Field static def clientSecret = "C63CLXOmwNaqLTB2xXo6QIWGwwBamcPuaul"
@Field static def xApiKey = "p7ndMoj61npRZP5CVz9v4Uj0bG769xy6758QRBPb"

def mainPage() {           
        def robots = [:]
        // Get robots if we don't have them already
        if ((state.robots?.size()?:0) == 0 && state.token?.trim()) {
            getLitterRobots()
        }
        if (state.robots) {
            robots = state.robots
            robots.sort { it.value }
        }
            
        dynamicPage(name: "mainPage", install: true, uninstall: true) {
            if (robots) {
                section("Select which Litter-Robots to use:") {
                    input(name: "robots", type: "enum", title: "Litter-Robots", required: false, multiple: true, options: robots)
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
            input("email", "email", title: "Email", description: "Litter-Robot Email", required: true)
            input("password", "password", title: "Password", description: "Litter-Robot password", required: true)
        }
    }
}

def authResultPage() {
    log.info "Attempting login with specified credentials..."
    
    doLogin()
    
    // Check if login was successful
    if (state.token == null) {
        dynamicPage(name: "authResultPage", nextPage: "authPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please check your credentials and try again.")
            }
        }
    } else {
        if (state.userId == null)
            getUserId()
        dynamicPage(name: "authResultPage", nextPage: "mainPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please click next to continue setting up your Litter-Robot.")
            }
        }
    }
}

boolean doLogin(){
    def loggedIn = false
    def resp = authApi(email,password)
    state.loginAttempt = (state.loginAttempt ?: 0) + 1
    
    switch (resp.status) {
        case 403:
            state.loggedIn = false
            state.loginResponse = resp.data.message == "Forbidden" ? "Access forbidden: invalid API key" : resp.data.message
            state.refresh_token = null
            state.token = null
            state.robots = null
            state.token_expiration = null
            break
        case 401:
            state.loggedIn = false
            state.loginResponse = "Login unsuccessful"
            state.refresh_token = null
            state.token = null
            state.robots = null
            state.token_expiration = null
            break
        case 200:
            state.loginResponse = "Login successful"
            state.loggedIn = true
            state.token = resp.data.access_token
            state.refresh_token = resp.data.refresh_toke
            state.token_expiration = now() + (resp.data.expires_in*1000)-10000
            state.remove("loginAttempt")
            break
        default:
            state.loggedIn = false
            state.loginResponse = "Login unsuccessful"
            state.refresh_token = null
            state.token = null
            state.robots = null
            state.token_expiration = null
            break
    }

    loggedIn = state.loggedIn
    state.credentialStatus = loggedIn ? "[Connected]" : "[Disconnected]"
    return loggedIn
}

def reAuth() {
    // Do nothing but keep this for backwards compatibility
}

// Get the list of Litter-Robots
def getLitterRobots() {
    def data = doApiGet("/users/${state.userId}/robots", null)

    // save in state so we can re-use in settings
    def robots = [:]
    data.each {
        robots[[app.id, it.litterRobotId].join('.')] = it.litterRobotNickname
    }
    state.robots = robots
    data
}

def authApi(username, password) {
    def params = [
            uri: authHost,
            path: "/oauth/token",
            headers: [
                "Content-Type": "application/x-www-form-urlencoded"
            ],
            body: [
                client_id: clientId,
                client_secret: clientSecret,
                grant_type: "password",
                username: username,
                password: password
            ]
        ]

     try {
			def result
            httpPost(params) {resp->
                result = resp
            }
			return result
        } catch (groovyx.net.http.HttpResponseException e) {
            log.info e
            return e.response
        } catch (e) {
            log.error "Something went wrong: ${e}"
            return [error: e.message]
        }
}

def getUserId() {
    state.userId = doApiGet("/users",null)?.user?.userId
    log.debug state.userId
}

def doApiGet(path, query) {
    if (state.token_expiration <= now()) {
        doLogin()
    }
    
    def result
    def params = [
        uri: apiHost,
        path: path,
        query: query,
        headers: [
            "Authorization": state.token,
            "x-api-key": xApiKey
        ]
    ]
    httpGet(params) { resp -> 
        result = resp.data
    }
    return result
}

def doApiPatch(path, body) {
    if (state.token_expiration <= now()) {
        doLogin()
    }

    def result
    def params = [
        uri: apiHost,
        path:path,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: [
            "Content-Type": "application/json",
            "Authorization": state.token,
            "x-api-key": xApiKey
        ],
        body: body
    ]
    httpPatch(params) { resp -> 
        result = resp.data
    }
    return result
}

def doApiPost(path, body) {
    if (state.token_expiration <= now()) {
        doLogin()
    }
         
     def result
    def params = [
        uri: apiHost,
        path:path,
        requestContentType: "application/json",
        headers: [
            "User-Agent": "Litter-Robot/1.3.4 (com.autopets.whisker.ios; build:59; iOS 14.4.1) Alamofire/4.9.0",
            "Authorization": state.token,
            "x-api-key": xApiKey
        ],
        body: body
    ]
    try {

        httpPost(params) { resp -> 

            result = resp.data
        }
    }
    catch (e) {
        log.debug e

    }
    return result   
}



def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {		
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
                childDevice = addChildDevice("natekspencer", "Litter-Robot", deviceId, location.hubs[0]?.id, [label: state.robots[deviceId], completedSetup: true])	
            }	
            childDevices.add(childDevice)	
        } catch (e) {	
            log.error "Error creating device: ${e}"	
        }	
    }	
    	
    // set up polling only if we have child devices	
    if(childDevices.size() > 0) {	
        pollChildren()	
			
        schedule("0 0/${pollingInterval} * * * ?", pollChildren)	
    } else unschedule(pollChildren)	
}

def pollChildren() {
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
    def data = doApiPost("/users/${state.userId}/robots/${litterRobotId}/dispatch-commands", [litterRobotId: litterRobotId, command: command as String])
    return data
}

def getActivity(litterRobotId, limit=10) {
    def data = doApiGet("/users/${state.userId}/robots/${litterRobotId}/activity", [limit: limit])
    return data.activities
}

def resetDrawerGauge(litterRobotId, params) {
    return doApiPatch("/users/${state.userId}/robots/${litterRobotId}", [litterRobotNickname: params.name, cycleCapacity: params.capacity, cycleCount: 0, cyclesAfterDrawerFull: 0])
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
