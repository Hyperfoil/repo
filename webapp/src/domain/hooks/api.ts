import { fetchApi } from '../../services/api';
import { Hook } from './reducers';

const base = "/api/hook"
const endPoints = {
    base: ()=>`${base}`,
    crud:  (id: number)=> `${base}/${id}/`,
    list: ()=> `${base}/list/`,
    testHooks: (id: number)=> `${base}/test/${id}`,
}

export const all = () => {
    return fetchApi(endPoints.list(),null,'get');

}
export const add = (payload: Hook) => {
    return fetchApi(endPoints.base(),payload,'post')
}
export const get = (id: number) => {
    return fetchApi(endPoints.crud(id),null,'get');
}
export const remove = (id: number) => {
    return fetchApi(endPoints.crud(id),null,'delete');
}

export const fetchHooks = (testId: number) => {
    return fetchApi(endPoints.testHooks(testId), null, 'get')
}
