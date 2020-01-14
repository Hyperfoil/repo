import { fetchApi } from '../../services/api';

const base = "/api/run"
const endPoints = {
    
    getRun: runId => `${base}/${runId}/`,
    addRun: () => `${base}/`,
    listAll: ()=> `${base}/list/`,
    filter: (query, recurseToArrays) => `${base}/filter?query=${query}&recurseToArrays=${recurseToArrays}`,
    js: runId => `${base}/${runId}/js`,
    listByTest: testId => `${base}/list/${testId}`

}

export const all = () => {
    return fetchApi(endPoints.listAll(),null,'get');

}
export const get = (id,js) => {
    if(typeof js === "undefined" || js === null){   
        return fetchApi(endPoints.getRun(id),null,'get');
    }else{
        return fetchApi(endPoints.js(id),js,'post');
    }
}
export const byTest = (id,payload) => fetchApi(endPoints.listByTest(id),payload,'post');

export const filter = (query, recurseToArrays) => fetchApi(endPoints.filter(query, recurseToArrays),null,'get')